package com.tazifor.rtb.geo.controller;

import com.tazifor.rtb.geo.model.BBox;
import com.tazifor.rtb.geo.model.LatLon;
import com.tazifor.rtb.geo.model.Polygon;
import com.tazifor.rtb.geo.model.TileKey;
import com.tazifor.rtb.geo.service.CampaignTilePrecomputeService;
import com.tazifor.rtb.geo.util.Svg;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.*;

@RestController
@RequestMapping("/api/geo")
public class GeoController {
    private final CampaignTilePrecomputeService svc;
    private final SecureRandom rnd = new SecureRandom();

    public GeoController(CampaignTilePrecomputeService svc) { this.svc = svc; }

    // 1) Swap grid step quickly
    @PostMapping("/tiler/rect")
    public Map<String,Object> setRect(@RequestParam double dLat, @RequestParam double dLon) {
        svc.useRectGrid(dLat, dLon);
        return Map.of("tiler", svc.tilerName());
    }

    // 2) Load a demo polygon for a campaign (Yaoundé-ish rough quad, tweak as you like)
    @PostMapping("/campaign/{id}/load-demo")
    public Map<String,Object> loadDemo(@PathVariable String id,
                                       @RequestParam(defaultValue = "50") double coveragePercent) {
        Polygon poly = new Polygon(List.of(
            LatLon.of(3.88, 11.45), LatLon.of(3.88, 11.60),
            LatLon.of(3.97, 11.60), LatLon.of(3.97, 11.45)
        ));
        svc.loadCampaign(id, poly, coveragePercent);
        return Map.of("campaign", id, "allowedTiles", svc.getAllowed(id).size(), "tiler", svc.tilerName());
    }

    // 3) Quick membership check
    @GetMapping("/membership/{id}")
    public Map<String,Object> membership(@PathVariable String id, @RequestParam double lat, @RequestParam double lon) {
        boolean ok = svc.allows(id, LatLon.of(lat, lon));
        return Map.of("campaign", id, "lat", lat, "lon", lon, "allowed", ok);
    }

    // 4) SVG visualization: grid + polygon + allowed tile cells + random points colored
    @GetMapping(value="/viz/{id}", produces = MediaType.APPLICATION_XML_VALUE)
    public String viz(@PathVariable String id,
                      @RequestParam(defaultValue="3.86") double minLat,
                      @RequestParam(defaultValue="11.43") double minLon,
                      @RequestParam(defaultValue="3.99") double maxLat,
                      @RequestParam(defaultValue="11.63") double maxLon,
                      @RequestParam(defaultValue="300") int width,
                      @RequestParam(defaultValue="380") int height,
                      @RequestParam(defaultValue="300") int randomPoints) {

        var tiler = svc.tiler();
        var bbox = new BBox(minLat, minLon, maxLat, maxLon);
        var tiles = tiler.tilesCovering(bbox);
        var allowed = svc.getAllowed(id);

        // simple demo polygon same as load-demo (you can pass points later if you want)
        Polygon poly = new Polygon(List.of(
            LatLon.of(3.88, 11.45), LatLon.of(3.88, 11.60),
            LatLon.of(3.97, 11.60), LatLon.of(3.97, 11.45)
        ));

        List<LatLon> pts = new ArrayList<>();
        for (int i=0;i<randomPoints;i++) {
            double la = minLat + rnd.nextDouble()*(maxLat-minLat);
            double lo = minLon + rnd.nextDouble()*(maxLon-minLon);
            pts.add(LatLon.of(la, lo));
        }

        return Svg.worldToSvg(minLat, minLon, maxLat, maxLon, width, height, g -> {
            // Optimized grid rendering using SVG patterns
            if (tiler instanceof com.tazifor.rtb.geo.spi.RectGridTiler rectTiler) {
                // Define grid pattern based on tile size
                g.defineGridPattern("grid", rectTiler.getdLon(), rectTiler.getdLat(), "#ddd", 0.3);
                // Draw entire grid with single pattern-filled rectangle
                g.patternRect(bbox, "grid");
            } else {
                // Fallback for non-rectangular tilers: draw individual tiles
                for (TileKey k : tiles) {
                    g.rect(tiler.boundsOf(k), "#ddd", "none", 0.0, 0.3);
                }
            }

            // draw allowed tiles (yellow fill)
            for (TileKey k : allowed) {
                BBox b = tiler.boundsOf(k);
                if (bbox.contains(LatLon.of((b.minLat()+b.maxLat())/2,(b.minLon()+b.maxLon())/2))) {
                    g.rect(b, "#daa520", "#ffd700", 0.35, 0.6);
                }
            }
            // polygon outline
            g.polygon(poly, "#0a0", "none", 0.0, 1.5);

            // random points: green if allowed by tile, red otherwise
            for (LatLon p : pts) {
                boolean ok = allowed.contains(tiler.tileOf(p));
                g.circle(p, 2.0, ok ? "#080" : "#800", ok ? "#0c0" : "#c00", 0.9);
            }

            g.text("Tiler: " + svc.tilerName() + " | Campaign: " + id, minLon+0.002, maxLat-0.002, 12);
        });
    }

    // 5) Helper: map a lat/lon to a tile id quickly
    @GetMapping("/tile-of")
    public Map<String,String> tileOf(@RequestParam double lat, @RequestParam double lon) {
        var key = svc.tiler().tileOf(LatLon.of(lat, lon));
        return Map.of("tileId", key.id(), "tiler", svc.tilerName());
    }

    // 6) GeoJSON endpoint for interactive map visualization
    @GetMapping(value="/geojson/{id}", produces = "application/json")
    public Map<String, Object> geojson(@PathVariable String id,
                                       @RequestParam(defaultValue="3.86") double minLat,
                                       @RequestParam(defaultValue="11.43") double minLon,
                                       @RequestParam(defaultValue="3.99") double maxLat,
                                       @RequestParam(defaultValue="11.63") double maxLon,
                                       @RequestParam(defaultValue="300") int randomPoints,
                                       @RequestParam(defaultValue="false") boolean showSamplePoints) {
        var tiler = svc.tiler();
        var bbox = new BBox(minLat, minLon, maxLat, maxLon);
        var allowed = svc.getAllowed(id);

        // Campaign polygon (same as viz)
        Polygon poly = new Polygon(List.of(
            LatLon.of(3.88, 11.45), LatLon.of(3.88, 11.60),
            LatLon.of(3.97, 11.60), LatLon.of(3.97, 11.45)
        ));

        List<Map<String, Object>> features = new ArrayList<>();

        // Add campaign polygon as a feature
        List<List<Double>> polyCoords = new ArrayList<>();
        for (LatLon pt : poly.points()) {
            polyCoords.add(List.of(pt.lon(), pt.lat())); // GeoJSON is [lon, lat]
        }
        // Close the polygon
        if (!poly.points().isEmpty()) {
            LatLon first = poly.points().get(0);
            polyCoords.add(List.of(first.lon(), first.lat()));
        }

        features.add(Map.of(
            "type", "Feature",
            "geometry", Map.of(
                "type", "Polygon",
                "coordinates", List.of(polyCoords)
            ),
            "properties", Map.of(
                "name", "Campaign " + id,
                "stroke", "#0a0",
                "stroke-width", 2,
                "fill", "#0a0",
                "fill-opacity", 0.1
            )
        ));

        // Get all tiles covering the bbox to show both allowed and excluded
        var tiles = tiler.tilesCovering(bbox);

        // Add all tiles as rectangle features (allowed and excluded)
        for (TileKey k : tiles) {
            BBox b = tiler.boundsOf(k);
            if (bbox.contains(LatLon.of((b.minLat()+b.maxLat())/2,(b.minLon()+b.maxLon())/2))) {
                boolean isAllowed = allowed.contains(k);
                List<List<Double>> tileCoords = List.of(
                    List.of(b.minLon(), b.minLat()),
                    List.of(b.maxLon(), b.minLat()),
                    List.of(b.maxLon(), b.maxLat()),
                    List.of(b.minLon(), b.maxLat()),
                    List.of(b.minLon(), b.minLat()) // close
                );

                features.add(Map.of(
                    "type", "Feature",
                    "geometry", Map.of(
                        "type", "Polygon",
                        "coordinates", List.of(tileCoords)
                    ),
                    "properties", Map.of(
                        "tileId", k.id(),
                        "type", isAllowed ? "allowed-tile" : "excluded-tile",
                        "isAllowed", isAllowed,
                        "stroke", isAllowed ? "#daa520" : "#ff0000",
                        "stroke-width", isAllowed ? 1 : 2,
                        "fill", isAllowed ? "#ffd700" : "#ff6666",
                        "fill-opacity", isAllowed ? 0.35 : 0.2
                    )
                ));
            }
        }

        // Generate random test points
        List<LatLon> pts = new ArrayList<>();
        for (int i = 0; i < randomPoints; i++) {
            double la = minLat + rnd.nextDouble() * (maxLat - minLat);
            double lo = minLon + rnd.nextDouble() * (maxLon - minLon);
            pts.add(LatLon.of(la, lo));
        }

        // Add polygon vertices as markers
        int vertexNum = 1;
        for (LatLon vertex : poly.points()) {
            features.add(Map.of(
                "type", "Feature",
                "geometry", Map.of(
                    "type", "Point",
                    "coordinates", List.of(vertex.lon(), vertex.lat())
                ),
                "properties", Map.of(
                    "type", "polygon-vertex",
                    "vertexNumber", vertexNum++,
                    "lat", vertex.lat(),
                    "lon", vertex.lon()
                )
            ));
        }

        // Optionally show tile sample points (center + 4 corners)
        if (showSamplePoints) {
            for (TileKey k : tiles) {
                BBox tb = tiler.boundsOf(k);
                // Only show sample points for tiles near the polygon (within bbox)
                if (bbox.contains(LatLon.of((tb.minLat()+tb.maxLat())/2,(tb.minLon()+tb.maxLon())/2))) {
                    LatLon[] samples = {
                        new LatLon((tb.minLat() + tb.maxLat()) / 2, (tb.minLon() + tb.maxLon()) / 2), // center
                        new LatLon(tb.minLat(), tb.minLon()), // SW
                        new LatLon(tb.minLat(), tb.maxLon()), // SE
                        new LatLon(tb.maxLat(), tb.minLon()), // NW
                        new LatLon(tb.maxLat(), tb.maxLon())  // NE
                    };

                    String[] labels = {"Center", "SW", "SE", "NW", "NE"};
                    for (int i = 0; i < samples.length; i++) {
                        boolean isInside = com.tazifor.rtb.geo.util.Geo.pointInPolygon(samples[i], poly);
                        features.add(Map.of(
                            "type", "Feature",
                            "geometry", Map.of(
                                "type", "Point",
                                "coordinates", List.of(samples[i].lon(), samples[i].lat())
                            ),
                            "properties", Map.of(
                                "type", "sample-point",
                                "tileId", k.id(),
                                "position", labels[i],
                                "insidePolygon", isInside
                            )
                        ));
                    }
                }
            }
        }

        // Add random points as features (green if allowed, red if not)
        for (LatLon p : pts) {
            boolean isAllowed = allowed.contains(tiler.tileOf(p));
            features.add(Map.of(
                "type", "Feature",
                "geometry", Map.of(
                    "type", "Point",
                    "coordinates", List.of(p.lon(), p.lat())
                ),
                "properties", Map.of(
                    "type", "test-point",
                    "allowed", isAllowed,
                    "marker-color", isAllowed ? "#0c0" : "#c00",
                    "marker-size", "small"
                )
            ));
        }

        return Map.of(
            "type", "FeatureCollection",
            "features", features,
            "metadata", Map.of(
                "campaign", id,
                "tiler", svc.tilerName(),
                "allowedTiles", allowed.size(),
                "bbox", List.of(minLat, minLon, maxLat, maxLon)
            )
        );
    }
}
