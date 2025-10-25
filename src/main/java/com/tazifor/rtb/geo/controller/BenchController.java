package com.tazifor.rtb.geo.controller;

import com.tazifor.rtb.geo.model.BBox;
import com.tazifor.rtb.geo.service.BenchmarkService;
import com.tazifor.rtb.geo.service.CampaignTilePrecomputeService;
import com.tazifor.rtb.geo.spi.H3Tiler;
import com.tazifor.rtb.geo.spi.S2Tiler;
import com.tazifor.rtb.geo.spi.Tiler;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/geo/bench")
public class BenchController {

    private final CampaignTilePrecomputeService pre;
    private final BenchmarkService bench;

    // Store progress for async benchmarks
    private final ConcurrentHashMap<String, AtomicInteger> progressMap = new ConcurrentHashMap<>();

    public BenchController(CampaignTilePrecomputeService pre, BenchmarkService bench) {
        this.pre = pre;
        this.bench = bench;
    }

    /**
     * Run realistic RTB benchmark: M bid requests × N campaigns
     *
     * Examples:
     * GET /api/geo/bench/rtb?bidRequests=10000&campaigns=100&tilerType=rect
     * GET /api/geo/bench/rtb?bidRequests=10000&campaigns=100&tilerType=h3&resolution=8
     * GET /api/geo/bench/rtb?bidRequests=10000&campaigns=100&tilerType=s2&resolution=13
     */
    @GetMapping("/rtb")
    public Map<String, Object> runRtbBenchmark(
        @RequestParam(defaultValue = "rect") String tilerType,
        @RequestParam(required = false) Integer resolution,
        @RequestParam(defaultValue = "10000") int bidRequests,
        @RequestParam(defaultValue = "100") int campaigns,
        @RequestParam(defaultValue = "3.7") double minLat,
        @RequestParam(defaultValue = "11.3") double minLon,
        @RequestParam(defaultValue = "4.1") double maxLat,
        @RequestParam(defaultValue = "11.8") double maxLon,
        @RequestParam(defaultValue = "50") double coveragePercent
    ) {
        String benchId = "bench-" + System.currentTimeMillis();
        AtomicInteger progress = new AtomicInteger(0);
        progressMap.put(benchId, progress);

        try {
            BBox sampleBox = new BBox(minLat, minLon, maxLat, maxLon);

            // Select tiler based on type
            Tiler tiler = switch (tilerType.toLowerCase()) {
                case "h3" -> resolution != null ? new H3Tiler(resolution) : new H3Tiler();
                case "s2" -> resolution != null ? new S2Tiler(resolution) : new S2Tiler();
                default -> pre.tiler(); // RectGridTiler
            };

            // Generate synthetic campaigns
            List<BenchmarkService.Campaign> campaignList =
                bench.generateCampaigns(campaigns, sampleBox, tiler, coveragePercent);

            // Run benchmark with progress callback
            var result = bench.runRtbScenario(
                tiler,
                campaignList,
                sampleBox,
                bidRequests,
                progress::set
            );

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("benchId", benchId);
            out.put("scenario", "RTB: M bid requests × N campaigns");
            out.put("bidRequests", result.bidRequests());
            out.put("campaigns", result.campaigns());
            out.put("totalChecks", result.totalChecks());

            out.put("pointInPolygon", Map.of(
                "totalMs", result.pipTotalNanos() / 1_000_000.0,
                "msPerBid", result.pipMsPerBid(),
                "nsPerCheck", result.pipNsPerCheck(),
                "matches", result.pipMatches()
            ));

            out.put("tilePrecomputed", Map.of(
                "totalMs", result.tileTotalNanos() / 1_000_000.0,
                "msPerBid", result.tileMsPerBid(),
                "nsPerCheck", result.tileNsPerCheck(),
                "matches", result.tileMatches()
            ));

            out.put("speedup", Map.of(
                "factor", String.format("%.1fx", result.speedupX()),
                "improvement", String.format("%.1f%%", (result.speedupX() - 1) * 100)
            ));

            out.put("tiler", result.tilerName());
            out.put("matchAccuracy", result.pipMatches() == result.tileMatches() ? "✓ Exact match" : "⚠ Mismatch");

            return out;
        } finally {
            progressMap.remove(benchId);
        }
    }

    /**
     * Get progress of running benchmark
     */
    @GetMapping("/progress/{benchId}")
    public Map<String, Object> getProgress(@PathVariable String benchId) {
        AtomicInteger progress = progressMap.get(benchId);
        return Map.of(
            "benchId", benchId,
            "progress", progress != null ? progress.get() : 100,
            "status", progress != null ? "running" : "completed"
        );
    }
}
