package com.tazifor.rtb.geo.model;

import java.util.List;

/**
 * Represents a polygon as an <b>ordered list of latitude/longitude vertices</b>.
 * <p>
 * The order of the points determines how edges are connected:
 * each vertex {@code i} is connected to {@code i+1}, and
 * the last vertex automatically connects back to the first.
 * </p>
 *
 * <h3>Why ordering matters</h3>
 * <ul>
 *   <li>
 *     The vertices must be listed in a consistent order —
 *     either <b>clockwise</b> or <b>counter-clockwise</b> —
 *     to correctly define the polygon’s interior.
 *   </li>
 *   <li>
 *     The polygon is implicitly closed — you do <b>not</b> need to
 *     repeat the first vertex at the end.
 *   </li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * Polygon cityBoundary = new Polygon(List.of(
 *     LatLon.of(3.88, 11.45),  // southwest corner
 *     LatLon.of(3.88, 11.60),  // southeast corner
 *     LatLon.of(3.97, 11.60),  // northeast corner
 *     LatLon.of(3.97, 11.45)   // northwest corner
 * ));
 * }</pre>
 *
 * The polygon above forms a rectangle over part of Yaoundé.
 *  *
 *  * <h3>🧩 Vertex ordering diagrams</h3>
 *  * <pre>
 *     Counter-Clockwise (CCW)              Clockwise (CW)
 *
 *          4 ┌──────────┐ 3                  3 ┌──────────┐ 4
 *            │          │                      │          │
 *            │          │                      │          │
 *          1 └──────────┘ 2                  2 └──────────┘ 1
 *
 *     Points are connected 1→2→3→4→1      Points are connected 1→4→3→2→1
 *     CCW means “inside” is on the left   CW means “inside” is on the right
 *   </pre>
 */
public record Polygon(List<LatLon> points) {

    /**
     * Returns the ordered list of vertices forming this polygon.
     * <p>
     * The polygon is considered closed implicitly — that is,
     * an edge is always assumed between the last and first vertex.
     * </p>
     */
    public List<LatLon> pts() {
        return points;
    }
}
