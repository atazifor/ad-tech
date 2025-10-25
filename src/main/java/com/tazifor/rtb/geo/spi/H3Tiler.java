package com.tazifor.rtb.geo.spi;

import com.tazifor.rtb.geo.model.*;
import com.tazifor.rtb.geo.util.Geo;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@code H3Tiler} implements tile-based geo-indexing using Uber's H3 hexagonal grid system.
 * <p>
 * <strong>⬡ What is H3?</strong><br>
 * H3 is a hierarchical geospatial indexing system that divides the Earth into hexagonal cells.
 * Unlike rectangular grids (lat/lon), hexagons have uniform neighbor distance and better
 * approximate circular coverage areas, making them ideal for proximity queries and heatmaps.
 * </p>
 *
 * <h3>🔑 Key Advantages over Rectangular Grids</h3>
 * <ul>
 *   <li><strong>Uniform neighbors:</strong> Every hexagon (except pentagons at poles) has exactly 6 neighbors at equal distance</li>
 *   <li><strong>No orientation bias:</strong> Rectangles favor N-S-E-W directions; hexagons treat all directions equally</li>
 *   <li><strong>Better area approximation:</strong> Hexagons more closely approximate circles (ideal coverage shape)</li>
 *   <li><strong>Hierarchical:</strong> Coarse cells subdivide into 7 finer cells, enabling multi-resolution queries</li>
 *   <li><strong>Industry standard:</strong> Used by Uber, Foursquare, AirBnB for location analytics</li>
 * </ul>
 *
 * <h3>📐 Resolution Levels</h3>
 * H3 supports 16 resolution levels (0-15), where higher numbers mean finer granularity:
 * <table border="1" style="border-collapse: collapse;">
 *   <tr><th>Resolution</th><th>Avg Hexagon Edge</th><th>Avg Hexagon Area</th><th>Use Case</th></tr>
 *   <tr><td>0</td><td>~1,108 km</td><td>4,357,449 km²</td><td>Continental regions</td></tr>
 *   <tr><td>3</td><td>~59 km</td><td>12,393 km²</td><td>Metro areas</td></tr>
 *   <tr><td>6</td><td>~3.2 km</td><td>36.1 km²</td><td>Neighborhoods</td></tr>
 *   <tr><td>8</td><td>~461 m</td><td>0.74 km²</td><td>City blocks (typical RTB)</td></tr>
 *   <tr><td>10</td><td>~66 m</td><td>15,047 m²</td><td>Buildings</td></tr>
 *   <tr><td>12</td><td>~9.4 m</td><td>308 m²</td><td>Rooms</td></tr>
 *   <tr><td>15</td><td>~0.5 m</td><td>0.9 m²</td><td>Centimeter-level</td></tr>
 * </table>
 *
 * <h3>🎯 Typical RTB Configuration</h3>
 * For real-time bidding geo-targeting:
 * <ul>
 *   <li><strong>Resolution 8</strong> (~461m edges): Good balance between accuracy and performance</li>
 *   <li><strong>Resolution 9</strong> (~174m edges): Finer accuracy for dense urban areas</li>
 *   <li><strong>Resolution 7</strong> (~1.2km edges): Broader coverage for suburban/rural</li>
 * </ul>
 *
 * <h3>⚡ Performance Characteristics</h3>
 * <ul>
 *   <li><strong>Encoding:</strong> (lat,lon) → H3 index: ~50-100ns (faster than rect grid)</li>
 *   <li><strong>Decoding:</strong> H3 index → (lat,lon): ~50-100ns</li>
 *   <li><strong>Storage:</strong> 64-bit integer (8 bytes) vs 16+ bytes for string-based rect IDs</li>
 *   <li><strong>Neighbor lookup:</strong> O(1) via bit manipulation</li>
 * </ul>
 *
 * <h3>🔧 Implementation Note</h3>
 * <strong>This is a PRODUCTION implementation using the official H3 library.</strong>
 * <p>
 * Maven dependency:
 * <pre>{@code
 * <dependency>
 *   <groupId>com.uber</groupId>
 *   <artifactId>h3</artifactId>
 *   <version>4.1.1</version>
 * </dependency>
 * }</pre>
 * </p>
 * <p>
 * This implementation uses real H3 cell IDs and boundaries, providing:
 * <ul>
 *   <li>Accurate hexagonal tiling with proper icosahedron-based projection</li>
 *   <li>Production-ready cell IDs that are globally consistent</li>
 *   <li>True H3 performance characteristics</li>
 *   <li>Compatibility with other H3-based systems</li>
 * </ul>
 * </p>
 *
 * <h3>📚 Further Reading</h3>
 * <ul>
 *   <li>H3 Documentation: https://h3geo.org/</li>
 *   <li>Uber Engineering Blog: "H3: Uber's Hexagonal Hierarchical Spatial Index"</li>
 *   <li>Interactive Explorer: https://wolf-h3-viewer.glitch.me/</li>
 * </ul>
 *
 * @author Amin Taz
 * @see Tiler
 * @see RectGridTiler
 * @see S2Tiler
 */
public class H3Tiler implements Tiler {

    private final H3Core h3;
    private final int resolution;

    /**
     * Creates an H3 tiler at the specified resolution level.
     *
     * @param resolution H3 resolution (0-15), where 8 is typical for RTB (~461m hexagon edges)
     */
    public H3Tiler(int resolution) {
        if (resolution < 0 || resolution > 15) {
            throw new IllegalArgumentException("H3 resolution must be 0-15, got: " + resolution);
        }
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize H3Core", e);
        }
        this.resolution = resolution;
    }

    /**
     * Default constructor using resolution 8 (optimal for RTB: ~461m hexagon edges).
     */
    public H3Tiler() {
        this(8);
    }

    @Override
    public TileKey tileOf(LatLon p) {
        // Use real H3 library to get cell index
        long h3Index = h3.latLngToCell(p.lat(), p.lon(), resolution);
        return new TileKey(Long.toHexString(h3Index));
    }

    @Override
    public BBox boundsOf(TileKey key) {
        // Parse H3 cell ID and get real boundary
        long h3Index = Long.parseUnsignedLong(key.id(), 16);
        List<LatLng> boundary = h3.cellToBoundary(h3Index);

        // Calculate bounding box from the hexagon boundary
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        for (LatLng coord : boundary) {
            minLat = Math.min(minLat, coord.lat);
            maxLat = Math.max(maxLat, coord.lat);
            minLon = Math.min(minLon, coord.lng);
            maxLon = Math.max(maxLon, coord.lng);
        }

        return new BBox(minLat, minLon, maxLat, maxLon);
    }

    @Override
    public Set<TileKey> tilesCovering(BBox bbox) {
        // Create polygon from bbox corners for H3 polyfill
        List<LatLng> bboxPolygon = List.of(
            new LatLng(bbox.minLat(), bbox.minLon()),
            new LatLng(bbox.minLat(), bbox.maxLon()),
            new LatLng(bbox.maxLat(), bbox.maxLon()),
            new LatLng(bbox.maxLat(), bbox.minLon())
        );

        // Use H3's polyfill to get all hexagons covering this polygon
        List<Long> h3Indexes = h3.polygonToCells(bboxPolygon, null, resolution);

        return h3Indexes.stream()
            .map(idx -> new TileKey(Long.toHexString(idx)))
            .collect(Collectors.toSet());
    }

    @Override
    public Set<TileKey> tilesForPolygon(Polygon poly, double coverageThresholdPercent) {
        // Get bounding box
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        for (LatLon pt : poly.points()) {
            minLat = Math.min(minLat, pt.lat());
            maxLat = Math.max(maxLat, pt.lat());
            minLon = Math.min(minLon, pt.lon());
            maxLon = Math.max(maxLon, pt.lon());
        }

        BBox bbox = new BBox(minLat, minLon, maxLat, maxLon);
        Set<TileKey> candidates = tilesCovering(bbox);
        Set<TileKey> allowed = new HashSet<>();

        // Filter by coverage
        for (TileKey tk : candidates) {
            BBox tb = boundsOf(tk);

            // Sample center + 6 vertices of hexagon (simplified)
            LatLon[] samples = {
                new LatLon((tb.minLat() + tb.maxLat()) / 2, (tb.minLon() + tb.maxLon()) / 2), // center
                new LatLon(tb.minLat(), (tb.minLon() + tb.maxLon()) / 2), // bottom
                new LatLon(tb.maxLat(), (tb.minLon() + tb.maxLon()) / 2), // top
                new LatLon((tb.minLat() + tb.maxLat()) / 2, tb.minLon()), // left
                new LatLon((tb.minLat() + tb.maxLat()) / 2, tb.maxLon())  // right
            };

            int inside = 0;
            for (LatLon s : samples) {
                if (Geo.pointInPolygon(s, poly)) inside++;
            }

            double coverage = (inside * 100.0) / samples.length;
            if (coverage >= coverageThresholdPercent) {
                allowed.add(tk);
            }
        }

        return allowed;
    }

    @Override
    public String name() {
        return "h3-res" + resolution;
    }
}