package com.tazifor.rtb.geo.util;

import com.tazifor.rtb.geo.model.LatLon;
import com.tazifor.rtb.geo.model.Polygon;

import java.util.List;

public final class Geo {
    private Geo() {}

    /**
     * Determines whether a point lies inside a polygon using the
     * <b>ray-casting</b> (even–odd rule) algorithm.
     * <p>
     * Imagine drawing a horizontal ray from the test point
     * extending infinitely to the right.  Each time the ray
     * crosses an edge of the polygon, the "inside/outside" state
     * toggles.  After testing all edges:
     * <ul>
     *   <li>odd number of crossings → point is <b>inside</b></li>
     *   <li>even number of crossings → point is <b>outside</b></li>
     * </ul>
     *
     * <h3>🧠 Intuitive picture</h3>
     * <pre>
     *      Polygon edges (CCW)
     *
     *         (y increasing ↑)
     *
     *               (3) •─────• (2)
     *                    │     │
     *     test point → ● │     │
     *                    │     │
     *               (4) •─────• (1)
     *
     *     ───────────────▶  Ray shoots right →
     *
     *   The horizontal ray from the test point crosses
     *   the left edge once → odd crossings → inside.
     * </pre>
     *
     * <h3>Algorithm logic</h3>
     * <pre>{@code
     * for each edge (vertex j → vertex i):
     *     if the ray crosses that edge:
     *         flip the 'inside' flag
     * }</pre>
     *
     * The test for intersection:
     * <pre>{@code
     * ((yi >= p.lat()) != (yj >= p.lat())) &&
     * (p.lon() < (xj - xi) * (p.lat() - yi) / (yj - yi) + xi)
     * }</pre>
     *
     * Explanation:
     * <ul>
     *   <li><b>(yi >= p.lat()) != (yj >= p.lat())</b> → the edge spans the
     *       horizontal line at the point's latitude (one vertex above or at,
     *       one below). Using >= includes boundary points.</li>
     *   <li><b>(p.lon() < ...)</b> → the intersection of that edge with
     *       the ray lies to the right of the point’s longitude.</li>
     *   <li>If both are true, the ray "hits" the edge → toggle inside/outside.</li>
     * </ul>
     *
     * <h3>Loop structure note</h3>
     * The pattern <code>for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++)</code>
     * means:
     * <ul>
     *   <li>{@code j} starts as the last vertex (connecting back to the first).</li>
     *   <li>{@code i} increments normally each iteration.</li>
     *   <li>Before the next loop, {@code j = i} so the next edge is (j→i).</li>
     *   <li>This way, every edge — including the last-to-first — is tested.</li>
     * </ul>
     *
     * <h3>When it works</h3>
     * Works reliably for:
     * <ul>
     *   <li>simple (non-self-intersecting) polygons</li>
     *   <li>both convex and concave shapes</li>
     * </ul>
     *
     * @param p the test point (latitude/longitude)
     * @param polygon the polygon to test against (ordered vertex list)
     * @return {@code true} if the point lies inside the polygon; {@code false} otherwise
     */
    public static boolean pointInPolygon(LatLon p, Polygon polygon) {
        List<LatLon> pts = polygon.points();

        // First check if point is exactly on an edge or vertex (with small epsilon for floating point)
        double epsilon = 1e-10;
        for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
            double xi = pts.get(i).lon(), yi = pts.get(i).lat();
            double xj = pts.get(j).lon(), yj = pts.get(j).lat();

            // Check if point is on this edge
            double minX = Math.min(xi, xj), maxX = Math.max(xi, xj);
            double minY = Math.min(yi, yj), maxY = Math.max(yi, yj);

            if (p.lon() >= minX - epsilon && p.lon() <= maxX + epsilon &&
                p.lat() >= minY - epsilon && p.lat() <= maxY + epsilon) {
                // Point is within bounding box of edge, check if it's on the line
                double crossProduct = (p.lat() - yi) * (xj - xi) - (p.lon() - xi) * (yj - yi);
                if (Math.abs(crossProduct) < epsilon) {
                    return true; // Point is on the edge, consider it inside
                }
            }
        }

        // Ray casting for interior points
        boolean inside = false;
        for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
            double xi = pts.get(i).lon(), yi = pts.get(i).lat();
            double xj = pts.get(j).lon(), yj = pts.get(j).lat();
            boolean intersect = ((yi > p.lat()) != (yj > p.lat())) &&
                (p.lon() < (xj - xi) * (p.lat() - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    // Haversine distance in meters
    public static double distanceMeters(LatLon a, LatLon b) {
        double R = 6371_000; // meters
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double la1 = Math.toRadians(a.lat()), la2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat/2)*Math.sin(dLat/2) +
            Math.cos(la1)*Math.cos(la2) * Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }
}
