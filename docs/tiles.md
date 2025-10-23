# 🌍 From Latitude & Longitude to Tiles

### A Visual, Hands-On Lab for Understanding Geo-Targeting in Real-Time Bidding (RTB)

---

## 1️⃣ Why this project exists

Imagine you open **ESPN.com**, and the ad slot near the headline wants to show you a Nike ad —
but *only* if you’re in or near **Yaoundé**.

Your browser (or phone) sends a tiny ad request that includes your **latitude and longitude** —
numbers like `3.892` (latitude) and `11.509` (longitude).

The ad system has only **tens of milliseconds** to decide:

> “Does this location fall inside one of Nike’s targeted areas?”

That check — “is this point inside this polygon?” — sounds easy, but when you handle **millions of requests per second**, doing heavy geometric math every time becomes too slow and expensive.

That’s where **tiles** come in.

---

## 2️⃣ What are “tiles”?

Think of covering the whole Earth with a **grid** — like graph paper laid over a globe.
Each small square on that grid has an **ID** (like `R312_C640` meaning row 312, column 640).

Each grid cell is called a **tile**.

If we know which tiles belong to a campaign’s allowed area, we no longer have to do geometry.
At serve-time, we just check:

```
tile_id = tiler.tileOf(lat, lon)
allowed = allowed_tiles[campaign_id]
return tile_id in allowed
```

That’s a **dictionary lookup (O(1))**, lightning fast.

---

## 3️⃣ Why bother visualizing?

Tiles are invisible in normal maps.
If you can *see* them — how they overlap with a city or polygon — you start to understand:

* Why a **1 km** tile is faster but coarse.
* Why **smaller** tiles fit boundaries better but take more memory.
* Where false positives and negatives appear near edges.

This lab makes those trade-offs visible.

---

## 4️⃣ What this Spring Boot module does

This package (`com.tazifor.rtb.geo`) turns your Spring Boot project into a **tiny geo-tiling playground**.

It lets you:

1. Create a simple **rectangular grid** over part of the world (you choose cell size).
2. Define a **polygon** — for example, a city boundary.
3. **Precompute** which tiles fall inside the polygon.
4. **Check** whether any latitude/longitude pair is inside the allowed region.
5. **Visualize** everything as an SVG map with:

    * grid lines,
    * the polygon outline,
    * allowed tiles shaded,
    * random “user” points colored green/red.

You can swap in smarter tilers later (H3 or S2), but first you’ll *see* how basic tiling works.

---

## 5️⃣ Folder structure

```
src/main/java/com/tazifor/rtb/geo/
  LatLon.java                   ← simple coordinate pair
  BBox.java                     ← bounding box utilities
  TileKey.java                  ← string ID like R100_C200
  Polygon.java                  ← list of LatLon forming a shape
  Tiler.java                    ← generic interface for any tiling method
  RectGridTiler.java            ← our simple lat/lon grid implementation
  Geo.java                      ← math helpers (point-in-polygon, distance)
  CampaignTilePrecomputeService.java ← builds allowed tile sets
  Svg.java                      ← draws polygons/tiles/points as SVG
  GeoController.java            ← REST API playground
```

---

## 6️⃣ How it works step by step

1. **Quantize the Earth**

    * Choose grid step sizes, e.g. `0.01°` (~1 km north–south).
    * Each `(lat,lon)` pair now maps to one tile ID.

2. **Define a region (polygon)**

    * Example: a rectangular area over Yaoundé.

3. **Precompute allowed tiles**

    * For every tile whose center or corners lie inside the polygon, mark it “allowed.”

4. **At request time**

    * Convert the user’s `(lat,lon)` to `tile_id`.
    * Check if `tile_id` is in the campaign’s allowed set.
      → Instant yes/no.

5. **Visualize**

    * The `/api/geo/viz/{campaign}` endpoint draws an SVG showing the grid, polygon, allowed tiles, and random test points.

---

## 7️⃣ Try it out

### 1. Start your Spring Boot app

### 2. Choose grid size (≈ 1 km)

```bash
POST /api/geo/tiler/rect?dLat=0.01&dLon=0.01
```

### 3. Load a demo polygon for a campaign

```bash
POST /api/geo/campaign/nike/load-demo?coveragePercent=40
```

### 4. See it

Open in browser:
`http://localhost:8080/api/geo/viz/nike`

You’ll see:

* light gray grid = world tiles,
* green outline = polygon,
* shaded cells = allowed tiles,
* green/red dots = random user locations (allowed or not).

### 5. Test specific points

```bash
GET /api/geo/membership/nike?lat=3.915&lon=11.548
```

### 6. Shrink tiles (≈ 550 m) and compare

```bash
POST /api/geo/tiler/rect?dLat=0.005&dLon=0.005
POST /api/geo/campaign/nike/load-demo?coveragePercent=40
GET  /api/geo/viz/nike
```

---

## 8️⃣ What you’ll learn by watching

| Tile Size      | Pros                   | Cons                              | Visual Effect                   |
| -------------- | ---------------------- | --------------------------------- | ------------------------------- |
| Large (0.02°)  | Few tiles, fast lookup | Rough edges, many false positives | Tiles spill outside the polygon |
| Small (0.005°) | Accurate boundaries    | More tiles, more memory           | Edges fit closely               |

Seeing this visually teaches the core trade-off of all geo-targeting systems.

---

## 9️⃣ How to interpret the SVG

* **Gray grid lines** → the tiling of the Earth.
* **Green polygon** → the true geofence (target area).
* **Gray filled cells** → tiles marked as allowed (precomputed).
* **Green dots** → simulated users inside allowed tiles.
* **Red dots** → users outside allowed tiles.

If you zoom in (e.g., open SVG in VS Code and scale up), you’ll see how changing grid size affects accuracy.

---

## 🔟 Visualization Options

This project offers **two ways** to visualize your geo-targeting:

### Option 1: Interactive Web Map (Recommended)

**Best for:** Production use, exploration, presentations

Visit: `http://localhost:8080/map.html`

**Features:**
- 🗺️ **Interactive pan & zoom** - Powered by Leaflet.js with OpenStreetMap
- 🎯 **Click tiles** - See tile IDs and properties in popups
- 🔘 **Layer controls** - Toggle allowed tiles and polygon on/off
- 🔄 **Dynamic loading** - Switch between campaigns without refresh
- 📱 **Mobile-friendly** - Touch controls work seamlessly
- ⚡ **Fast & lightweight** - ~2-5KB GeoJSON vs 300KB+ SVG

**How it works:**
```bash
# 1. Load a campaign
POST /api/geo/campaign/nike/load-demo

# 2. Open the map
http://localhost:8080/map.html

# 3. Or fetch raw GeoJSON
GET /api/geo/geojson/nike
```

**Technical details:**
- Uses the `/api/geo/geojson/{id}` endpoint
- Returns standard GeoJSON FeatureCollection
- Each tile is a polygon feature with metadata
- Campaign boundary rendered as separate polygon feature

**File size comparison:**
```
Traditional SVG (all tiles):    315 KB  ❌ Slow rendering
Optimized SVG (pattern):         ~8 KB  ✓ Better
GeoJSON (interactive map):      2-5 KB  ✓✓ Best
```

### Option 2: Optimized SVG Visualization

**Best for:** Static reports, debugging, offline analysis

Visit: `http://localhost:8080/api/geo/viz/nike`

**Optimizations implemented:**
- ✅ **Pattern-based grid** - Single SVG pattern instead of thousands of individual `<rect>` elements
- ✅ **Reduced DOM size** - 97% smaller SVG files
- ✅ **Faster rendering** - Browsers render patterns much faster
- ✅ **Cleaner output** - No floating-point precision errors

**Before optimization:**
```xml
<!-- 2,600+ individual rect elements -->
<rect x='1.0658141036401446E-11' y='365.384615384617' .../>
<rect x='7.500000000003797' y='365.384615384617' .../>
<rect x='14.999999999996936' y='365.384615384617' .../>
<!-- ... 2,597 more ... -->
```

**After optimization:**
```xml
<!-- Single pattern definition + one pattern-filled rect -->
<defs>
  <pattern id='grid' width='7.5' height='14.6' ...>
    <rect width='7.5' height='14.6' stroke='#ddd'/>
  </pattern>
</defs>
<rect x='0' y='0' width='300' height='380' fill='url(#grid)'/>
```

**Implementation:**
```java
// Define grid pattern based on tile size
g.defineGridPattern("grid", tileWidthLon, tileHeightLat, "#ddd", 0.3);

// Draw entire grid with single pattern-filled rectangle
g.patternRect(bbox, "grid");
```

### Choosing Between Them

| Feature                  | Interactive Map | Optimized SVG |
|-------------------------|-----------------|---------------|
| **Pan & Zoom**          | ✅ Yes          | ❌ No         |
| **Click Interactions**  | ✅ Yes          | ❌ No         |
| **File Size**           | 2-5 KB          | ~8 KB         |
| **Real Map Context**    | ✅ OSM tiles    | ❌ Abstract   |
| **Offline Use**         | ❌ Needs CDN    | ✅ Standalone |
| **Mobile Friendly**     | ✅ Yes          | ⚠️ Limited    |
| **Print/Export**        | ⚠️ Screenshot   | ✅ Direct     |
| **Scale to 1M+ tiles**  | ✅ Yes          | ⚠️ Limited    |

**Recommendation:** Use the **Interactive Map** for development and production. Use **SVG** for generating static reports or when you need offline/embedded visualizations.

### Landing Page

A helpful landing page is available at:
```
http://localhost:8080/
```

This shows both visualization options and lists available API endpoints.

---

## 1️⃣1️⃣ What's next (optional extensions)

| Idea                        | What it teaches                                                          |
| --------------------------- | ------------------------------------------------------------------------ |
| **Radius geofence**         | Replace polygon with “within X km of (lat,lon)” using Haversine distance |
| **Accuracy evaluator**      | Compute precision / recall vs true polygon membership                    |
| **H3 / S2 tilers**          | Use industry-grade spherical grids                                       |
| **Redis cache**             | Store `(campaign_id, tile_id)` for real O(1) production lookups          |
| **Multi-resolution tiling** | Use coarse grid for interiors, fine grid for boundaries                  |

---

## 🧠 Key takeaway

Tiles turn slow geometric math into instant lookups.
This lab shows — visually — how a simple grid can power real-time geo-targeting decisions inside an RTB engine.

Once you understand this picture, you can move on to more advanced tile systems like **H3** or **S2**, or integrate your precomputed tiles into an actual ad-decision pipeline.