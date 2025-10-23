package com.tazifor.rtb.geo.service;

import com.tazifor.rtb.geo.model.BBox;
import com.tazifor.rtb.geo.model.LatLon;
import com.tazifor.rtb.geo.model.Polygon;
import com.tazifor.rtb.geo.model.TileKey;
import com.tazifor.rtb.geo.spi.RectGridTiler;
import com.tazifor.rtb.geo.spi.Tiler;
import com.tazifor.rtb.geo.util.Geo;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@Service
public class BenchmarkService {

    /**
     * Represents a campaign with polygon and precomputed tiles
     */
    public record Campaign(String id, Polygon polygon, Set<TileKey> allowedTiles) {}

    /**
     * Results for realistic RTB benchmark: M bid requests × N campaigns
     */
    public record RtbBenchResult(
        int bidRequests,
        int campaigns,
        int totalChecks,           // bidRequests × campaigns
        long pipTotalNanos,
        long tileTotalNanos,
        double pipNsPerCheck,      // ns per (1 bid × 1 campaign)
        double tileNsPerCheck,
        double pipMsPerBid,        // ms per bid request (checking all campaigns)
        double tileMsPerBid,
        double speedupX,
        int pipMatches,
        int tileMatches,
        String tilerName
    ) {}

    /**
     * REALISTIC RTB SCENARIO:
     * M bid requests, each must check geo-targeting against N campaigns.
     *
     * This simulates the actual RTB decision flow:
     * For each bid request (user location):
     *   - Approach 1 (PIP): Check point-in-polygon for all N campaigns
     *   - Approach 2 (Tile): Compute tile once, lookup in N precomputed sets
     */
    public RtbBenchResult runRtbScenario(
        Tiler tiler,
        List<Campaign> campaigns,
        BBox sampleBox,
        int bidRequests,
        Consumer<Integer> progressCallback  // Reports progress (0-100)
    ) {
        // Generate M random bid request locations
        LatLon[] bidLocations = new LatLon[bidRequests];
        for (int i = 0; i < bidRequests; i++) {
            bidLocations[i] = randomPoint(sampleBox);
        }

        // JIT warmup: Run both approaches 1000x to ensure JVM optimization
        warmup(tiler, campaigns, sampleBox);

        int totalChecks = bidRequests * campaigns.size();
        int pipMatches = 0, tileMatches = 0;

        // ==========================================
        // APPROACH 1: Point-in-Polygon (ray casting)
        // ==========================================
        long t0 = System.nanoTime();
        for (int i = 0; i < bidRequests; i++) {
            LatLon bid = bidLocations[i];

            // Check this bid against ALL campaigns (realistic RTB flow)
            for (Campaign c : campaigns) {
                if (Geo.pointInPolygon(bid, c.polygon)) {
                    pipMatches++;
                }
            }

            // Report progress every 10%
            if (progressCallback != null && i % Math.max(1, bidRequests / 10) == 0) {
                progressCallback.accept((i * 50) / bidRequests); // 0-50%
            }
        }
        long t1 = System.nanoTime();

        // ==========================================
        // APPROACH 2: Tile-based (precomputed lookup)
        // ==========================================
        long t2 = System.nanoTime();
        for (int i = 0; i < bidRequests; i++) {
            LatLon bid = bidLocations[i];

            // KEY OPTIMIZATION: Compute tile ONCE per bid request
            TileKey bidTile = tiler.tileOf(bid);

            // Check this tile against ALL campaigns (O(1) per campaign)
            for (Campaign c : campaigns) {
                if (c.allowedTiles.contains(bidTile)) {
                    tileMatches++;
                }
            }

            // Report progress every 10%
            if (progressCallback != null && i % Math.max(1, bidRequests / 10) == 0) {
                progressCallback.accept(50 + (i * 50) / bidRequests); // 50-100%
            }
        }
        long t3 = System.nanoTime();

        long pipNanos = (t1 - t0);
        long tileNanos = (t3 - t2);

        return new RtbBenchResult(
            bidRequests,
            campaigns.size(),
            totalChecks,
            pipNanos,
            tileNanos,
            pipNanos / (double) totalChecks,
            tileNanos / (double) totalChecks,
            pipNanos / 1_000_000.0 / bidRequests,
            tileNanos / 1_000_000.0 / bidRequests,
            pipNanos / (double) Math.max(1, tileNanos),
            pipMatches,
            tileMatches,
            tiler.name()
        );
    }

    /**
     * Generate N synthetic campaigns with varying polygon complexity
     */
    public List<Campaign> generateCampaigns(
        int count,
        BBox area,
        Tiler tiler,
        double coveragePercent
    ) {
        List<Campaign> campaigns = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            // Generate random rectangle within the area
            double size = lerp(0.02, 0.08); // 2-8km roughly
            double centerLat = lerp(area.minLat() + size/2, area.maxLat() - size/2);
            double centerLon = lerp(area.minLon() + size/2, area.maxLon() - size/2);

            Polygon poly = new Polygon(List.of(
                LatLon.of(centerLat - size/2, centerLon - size/2),
                LatLon.of(centerLat - size/2, centerLon + size/2),
                LatLon.of(centerLat + size/2, centerLon + size/2),
                LatLon.of(centerLat + size/2, centerLon - size/2)
            ));

            // Precompute allowed tiles for this campaign
            Set<TileKey> allowed = computeAllowedTiles(tiler, poly, coveragePercent);

            campaigns.add(new Campaign("campaign-" + i, poly, allowed));
        }

        return campaigns;
    }

    /**
     * Precompute allowed tiles for a campaign (mimics CampaignTilePrecomputeService)
     */
    private Set<TileKey> computeAllowedTiles(Tiler tiler, Polygon polygon, double coveragePercent) {
        // Get polygon bounding box
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        for (LatLon pt : polygon.points()) {
            minLat = Math.min(minLat, pt.lat());
            maxLat = Math.max(maxLat, pt.lat());
            minLon = Math.min(minLon, pt.lon());
            maxLon = Math.max(maxLon, pt.lon());
        }

        BBox bbox = new BBox(minLat, minLon, maxLat, maxLon);
        Set<TileKey> tiles = tiler.tilesCovering(bbox);
        Set<TileKey> allowed = new HashSet<>();

        // Filter tiles by coverage
        for (TileKey tk : tiles) {
            BBox tb = tiler.boundsOf(tk);
            LatLon[] samples = {
                new LatLon((tb.minLat() + tb.maxLat()) / 2, (tb.minLon() + tb.maxLon()) / 2), // center
                new LatLon(tb.minLat(), tb.minLon()), // SW
                new LatLon(tb.minLat(), tb.maxLon()), // SE
                new LatLon(tb.maxLat(), tb.minLon()), // NW
                new LatLon(tb.maxLat(), tb.maxLon())  // NE
            };

            int inside = 0;
            for (LatLon s : samples) {
                if (Geo.pointInPolygon(s, polygon)) inside++;
            }

            double coverage = (inside * 100.0) / samples.length;
            if (coverage >= coveragePercent) {
                allowed.add(tk);
            }
        }

        return allowed;
    }

    /**
     * JIT warmup to ensure fair comparison
     */
    private void warmup(Tiler tiler, List<Campaign> campaigns, BBox area) {
        LatLon warmupPoint = randomPoint(area);
        TileKey warmupTile = tiler.tileOf(warmupPoint);

        for (int i = 0; i < 1000; i++) {
            for (Campaign c : campaigns) {
                Geo.pointInPolygon(warmupPoint, c.polygon);
                c.allowedTiles.contains(warmupTile);
            }
        }
    }

    private static LatLon randomPoint(BBox box) {
        return new LatLon(
            lerp(box.minLat(), box.maxLat()),
            lerp(box.minLon(), box.maxLon())
        );
    }

    private static double lerp(double a, double b) {
        return a + ThreadLocalRandom.current().nextDouble() * (b - a);
    }
}