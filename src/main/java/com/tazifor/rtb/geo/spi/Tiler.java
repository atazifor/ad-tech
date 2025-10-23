package com.tazifor.rtb.geo.spi;

import com.tazifor.rtb.geo.model.BBox;
import com.tazifor.rtb.geo.model.LatLon;
import com.tazifor.rtb.geo.model.Polygon;
import com.tazifor.rtb.geo.model.TileKey;

import java.util.Set;

/**
 * The {@code Tiler} interface defines a common contract for converting
 * latitude/longitude coordinates into discrete, indexable grid cells ("tiles"),
 * and for performing basic spatial operations on those tiles.
 * <p>
 * This abstraction allows different tiling strategies (e.g., rectangular grids,
 * hexagonal H3 cells, or Google's S2 cells) to be used interchangeably.
 * The key purpose is to make spatial queries—such as "is this location within a campaign’s target area?"—
 * extremely fast at runtime by precomputing which tiles intersect each campaign’s geofence.
 * </p>
 *
 * <h3>Motivation</h3>
 * Instead of evaluating complex geometry (point-in-polygon) for every ad request,
 * the system can:
 * <ol>
 *   <li>Quantize the world into tiles of fixed size or resolution.</li>
 *   <li>Precompute which tiles fall inside each campaign’s polygon.</li>
 *   <li>At request time, map (lat,lon) → tileId and perform an O(1) lookup.</li>
 * </ol>
 *
 * <h3>Example usage</h3>
 * <pre>{@code
 * Tiler tiler = new RectGridTiler(0.01, 0.01); // ~1km cells
 * TileKey tile = tiler.tileOf(LatLon.of(3.915, 11.548));
 * BBox bounds = tiler.boundsOf(tile);
 * Set<TileKey> covering = tiler.tilesForPolygon(myPolygon, 40.0);
 * }</pre>
 *
 * @author Amin Taz
 * @see RectGridTiler
 * @see LatLon
 * @see BBox
 * @see Polygon
 */
public interface Tiler {

    /**
     * Computes the unique tile identifier that contains the given geographic point.
     * <p>
     * Each tiler defines its own coordinate quantization and naming scheme.
     * For example, {@link RectGridTiler} converts latitude and longitude into
     * row/column indices and returns IDs such as {@code R123_C456}.
     * </p>
     *
     * @param p the geographic point (latitude and longitude)
     * @return a {@link TileKey} uniquely identifying the tile that contains {@code p}
     */
    TileKey tileOf(LatLon p);

    /**
     * Returns the geographic bounding box corresponding to a given tile.
     * <p>
     * The bounding box represents the min/max latitude and longitude
     * spanned by the tile’s edges. It can be used for visualization,
     * overlap estimation, or sampling within that tile.
     * </p>
     *
     * @param key the unique tile identifier
     * @return the {@link BBox} representing this tile’s boundaries
     */
    BBox boundsOf(TileKey key);

    /**
     * Enumerates all tiles whose bounding boxes intersect a given bounding box.
     * <p>
     * This is typically used as a coarse filter when determining which tiles
     * might intersect a polygon before performing finer inclusion tests.
     * </p>
     *
     * @param bbox the bounding box of interest
     * @return a set of {@link TileKey}s covering the supplied {@code bbox}
     */
    Set<TileKey> tilesCovering(BBox bbox);

    /**
     * Computes the set of tiles that should be considered "inside" or "allowed"
     * for a given polygonal area.
     * <p>
     * Implementations may decide inclusion based on:
     * <ul>
     *   <li>Whether the tile’s center lies within the polygon ("center-in" rule), or</li>
     *   <li>Whether a certain percentage of the tile’s area overlaps the polygon.</li>
     * </ul>
     * The {@code coverageThresholdPercent} parameter controls this behavior.
     * For example, {@code 0} means include if the center is inside; {@code 50}
     * means include if at least half of sampled points fall inside.
     * </p>
     *
     * @param poly the polygonal region (list of lat/lon vertices)
     * @param coverageThresholdPercent inclusion threshold between 0 and 100
     * @return a set of {@link TileKey}s whose tiles satisfy the coverage rule
     */
    Set<TileKey> tilesForPolygon(Polygon poly, double coverageThresholdPercent);

    /**
     * Returns a human-readable name describing the tiler configuration.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code "rect-0.01x0.01"} for a rectangular grid with 0.01° steps.</li>
     *   <li>{@code "h3-res8"} for an H3-based tiler at resolution 8.</li>
     * </ul>
     *
     * @return a friendly name useful for debugging and logs
     */
    String name();
}
