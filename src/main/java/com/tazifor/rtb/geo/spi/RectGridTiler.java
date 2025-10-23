package com.tazifor.rtb.geo.spi;

import com.tazifor.rtb.geo.model.*;
import com.tazifor.rtb.geo.util.Geo;

import java.util.*;

/**
 * {@code RectGridTiler} divides the Earth into a simple rectangular grid
 * based on constant latitude/longitude steps.
 * <p>
 * It is the most intuitive way to visualize how geographic coordinates
 * (latitude and longitude) can be quantized into discrete "tiles"
 * for fast geo lookups in systems like real-time bidding (RTB).
 * </p>
 *
 * <h3>🌍 Coordinate system recap</h3>
 *
 * <pre>
 *              Latitude ↑ (north)
 *                    +90° ─────────────────────── North Pole
 *                     │
 *                     │
 *                     │
 *              0° ────┼────── Equator (origin)
 *                     │
 *                     │
 *                    -90° ─────────────────────── South Pole
 *
 *   Longitude → (east)
 *  -180° ─────────────────────────────────────── +180°
 *       West                   0° Prime Meridian            East
 * </pre>
 *
 * Latitudes run from -90° to +90° (a total span of 180°).
 * Longitudes run from -180° to +180° (a total span of 360°).
 *
 * <h3>🧮 The offset trick</h3>
 * To make this grid zero-based (like a matrix with only positive indices),
 * we shift both ranges so that the smallest values start at 0:
 *
 * <pre>
 *   lat_shifted = lat + 90    → range [0, 180]
 *   lon_shifted = lon + 180   → range [0, 360]
 * </pre>
 *
 * After this transformation:
 * <ul>
 *   <li>The top-left tile of the world map becomes (row=0, col=0).</li>
 *   <li>The bottom-right tile becomes (row=180/dLat, col=360/dLon).</li>
 *   <li>Each cell is roughly square near the equator (distorts near poles).</li>
 * </ul>
 *
 * <h3>🧩 Example</h3>
 * If {@code dLat = 0.01} and {@code dLon = 0.01}, each tile is ≈1.1 km × 1.1 km.
 * <ul>
 *   <li>Latitude 0° → (0 + 90) / 0.01 = row 9000</li>
 *   <li>Longitude 0° → (0 + 180) / 0.01 = col 18000</li>
 *   <li>Tile ID = "R9000_C18000"</li>
 * </ul>
 *
 * @see Tiler
 * @see LatLon
 * @see Polygon
 */
public class RectGridTiler implements Tiler {

    /** Latitude step in degrees (vertical tile size). */
    private final double dLat;

    /** Longitude step in degrees (horizontal tile size). */
    private final double dLon;

    /**
     * Creates a rectangular tiler with the specified step sizes.
     *
     * @param dLat latitude step in degrees (tile height)
     * @param dLon longitude step in degrees (tile width)
     * @throws IllegalArgumentException if any step is ≤ 0
     */
    public RectGridTiler(double dLat, double dLon) {
        if (dLat <= 0 || dLon <= 0)
            throw new IllegalArgumentException("steps must be > 0");
        this.dLat = dLat;
        this.dLon = dLon;
    }

    /**
     * Converts a latitude into a zero-based row index.
     * <p>
     * Latitude naturally ranges from -90° to +90°.
     * By adding +90, we shift it into [0, 180], then divide by the step size
     * to find which row the coordinate belongs to.
     * </p>
     * Example: lat = 0° → (0 + 90) / 1° = row 90
     */
    private long row(double lat) {
        return (long) Math.floor((lat + 90.0) / dLat);
    }

    /**
     * Converts a longitude into a zero-based column index.
     * <p>
     * Longitude ranges from -180° to +180°.
     * By adding +180, we shift it into [0, 360], then divide by the step size
     * to find which column the coordinate belongs to.
     * </p>
     * Example: lon = 0° → (0 + 180) / 1° = column 180
     */
    private long col(double lon) {
        return (long) Math.floor((lon + 180.0) / dLon);
    }

    /**
     * Returns the unique tile that contains the given point.
     * <p>
     * The tile ID is formatted as {@code R{row}_C{col}},
     * e.g. {@code R9398_C19154}.
     * </p>
     */
    @Override
    public TileKey tileOf(LatLon p) {
        return new TileKey("R" + row(p.lat()) + "_C" + col(p.lon()));
    }

    /**
     * Computes the bounding box for a given tile ID.
     * <p>
     * The inverse of {@link #tileOf(LatLon)} — reconstructs the
     * geographic area covered by the tile.
     * </p>
     *
     * @param key a tile key such as "R123_C456"
     * @return the latitude/longitude bounds of that tile
     */
    @Override
    public BBox boundsOf(TileKey key) {
        String[] parts = key.id().split("_");
        long r = Long.parseLong(parts[0].substring(1)); // strip 'R'
        long c = Long.parseLong(parts[1].substring(1)); // strip 'C'

        // Reverse the earlier offset (+90, +180)
        double minLat = r * dLat - 90.0;
        double minLon = c * dLon - 180.0;

        return new BBox(minLat, minLon, minLat + dLat, minLon + dLon);
    }

    /**
     * Enumerates all tiles that intersect a given bounding box.
     * <p>
     * Useful for finding candidate tiles to test for polygon overlap.
     * </p>
     */
    @Override
    public Set<TileKey> tilesCovering(BBox bbox) {
        long r0 = (long) Math.floor((bbox.minLat() + 90.0) / dLat);
        long c0 = (long) Math.floor((bbox.minLon() + 180.0) / dLon);
        long r1 = (long) Math.floor((bbox.maxLat() + 90.0) / dLat);
        long c1 = (long) Math.floor((bbox.maxLon() + 180.0) / dLon);

        Set<TileKey> out = new LinkedHashSet<>();
        for (long r = r0; r <= r1; r++) {
            for (long c = c0; c <= c1; c++) {
                out.add(new TileKey("R" + r + "_C" + c));
            }
        }
        return out;
    }

    /**
     * Approximates which tiles fall within a polygon.
     * <p>
     * Each candidate tile (from the polygon's bounding box)
     * is sampled at five points: its center and four corners.
     * The percentage of points inside the polygon determines
     * whether the tile is "included" according to
     * {@code coverageThresholdPercent}.
     * </p>
     */
    @Override
    public Set<TileKey> tilesForPolygon(Polygon poly, double coverageThresholdPercent) {
        BBox bb = bbox(poly);
        Set<TileKey> cand = tilesCovering(bb);
        Set<TileKey> out = new LinkedHashSet<>();

        for (TileKey k : cand) {
            BBox tb = boundsOf(k);
            int inside = 0;

            // Sample points (center + 4 corners)
            LatLon[] samples = {
                new LatLon((tb.minLat() + tb.maxLat()) / 2, (tb.minLon() + tb.maxLon()) / 2),
                new LatLon(tb.minLat(), tb.minLon()),
                new LatLon(tb.minLat(), tb.maxLon()),
                new LatLon(tb.maxLat(), tb.minLon()),
                new LatLon(tb.maxLat(), tb.maxLon())
            };

            for (LatLon s : samples)
                if (Geo.pointInPolygon(s, poly)) inside++;

            double pct = (inside / (double) samples.length) * 100.0;
            if (pct >= coverageThresholdPercent)
                out.add(k);
        }
        return out;
    }

    /** Returns a descriptive name, e.g. "rect-0.01x0.01". */
    @Override
    public String name() {
        return "rect-" + dLat + "x" + dLon;
    }

    /**
     * Returns the latitude step size (tile height in degrees).
     */
    public double getdLat() {
        return dLat;
    }

    /**
     * Returns the longitude step size (tile width in degrees).
     */
    public double getdLon() {
        return dLon;
    }

    /**
     * Computes the bounding box that fully encloses the given polygon.
     * <p>
     * Helper for quickly finding candidate tiles to test.
     * </p>
     */
    public static BBox bbox(Polygon p) {
        double minLat = Double.MAX_VALUE, minLon = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        for (LatLon pt : p.points()) {
            minLat = Math.min(minLat, pt.lat());
            minLon = Math.min(minLon, pt.lon());
            maxLat = Math.max(maxLat, pt.lat());
            maxLon = Math.max(maxLon, pt.lon());
        }
        return new BBox(minLat, minLon, maxLat, maxLon);
    }
}
