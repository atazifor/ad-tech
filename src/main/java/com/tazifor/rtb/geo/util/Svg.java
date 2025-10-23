package com.tazifor.rtb.geo.util;

import com.tazifor.rtb.geo.model.BBox;
import com.tazifor.rtb.geo.model.LatLon;
import com.tazifor.rtb.geo.model.Polygon;

public final class Svg {
    private Svg() {}

    // World → simple linear projection to an SVG viewport
    public static String worldToSvg(double minLat, double minLon, double maxLat, double maxLon,
                                    int width, int height,
                                    RunnableWithStringBuilder painter) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <svg xmlns='http://www.w3.org/2000/svg' width='""" + width + "' height='" + height + "'>\n");

        painter.paint(new StringBuilderProxy(sb, minLat, minLon, maxLat, maxLon, width, height));

        sb.append("</svg>");
        return sb.toString();
    }

    public interface RunnableWithStringBuilder {
        void paint(StringBuilderProxy g);
    }

    public static final class StringBuilderProxy {
        private final StringBuilder sb;
        private final double minLat, minLon, maxLat, maxLon;
        private final int width, height;
        private boolean defsOpen = false;

        StringBuilderProxy(StringBuilder sb, double minLat, double minLon, double maxLat, double maxLon, int width, int height) {
            this.sb = sb; this.minLat=minLat; this.minLon=minLon; this.maxLat=maxLat; this.maxLon=maxLon;
            this.width=width; this.height=height;
        }

        private double x(double lon) { return (lon - minLon) / (maxLon - minLon) * width; }
        private double y(double lat) { return height - (lat - minLat) / (maxLat - minLat) * height; }

        private void ensureDefs() {
            if (!defsOpen) {
                sb.append("<defs>\n");
                defsOpen = true;
            }
        }

        public void closeDefs() {
            if (defsOpen) {
                sb.append("</defs>\n");
                defsOpen = false;
            }
        }

        public void polygon(Polygon p, String stroke, String fill, double opacity, double strokeWidth) {
            StringBuilder pts = new StringBuilder();
            for (LatLon pt : p.points()) {
                pts.append(x(pt.lon())).append(",").append(y(pt.lat())).append(" ");
            }
            sb.append("<polygon points='").append(pts).append("' stroke='").append(stroke)
                .append("' stroke-width='").append(strokeWidth).append("' fill='").append(fill)
                .append("' fill-opacity='").append(opacity).append("'/>\n");
        }

        public void rect(BBox b, String stroke, String fill, double opacity, double strokeWidth) {
            double X = x(b.minLon()), Y = y(b.maxLat());
            double W = x(b.maxLon()) - x(b.minLon());
            double H = y(b.minLat()) - y(b.maxLat());
            sb.append("<rect x='").append(X).append("' y='").append(Y).append("' width='")
                .append(W).append("' height='").append(H).append("' stroke='").append(stroke)
                .append("' stroke-width='").append(strokeWidth).append("' fill='").append(fill)
                .append("' fill-opacity='").append(opacity).append("'/>\n");
        }

        public void circle(LatLon p, double r, String stroke, String fill, double opacity) {
            sb.append("<circle cx='").append(x(p.lon())).append("' cy='").append(y(p.lat()))
                .append("' r='").append(r).append("' stroke='").append(stroke)
                .append("' fill='").append(fill).append("' fill-opacity='").append(opacity).append("'/>\n");
        }

        public void text(String text, double lon, double lat, int fontSize) {
            sb.append("<text x='").append(x(lon)).append("' y='").append(y(lat))
                .append("' font-size='").append(fontSize).append("' fill='#222'>")
                .append(text).append("</text>\n");
        }

        /**
         * Define a grid pattern based on geographic tile size
         * @param id Pattern ID to reference later
         * @param tileWidthLon Tile width in longitude degrees
         * @param tileHeightLat Tile height in latitude degrees
         * @param stroke Grid line color
         * @param strokeWidth Grid line width
         */
        public void defineGridPattern(String id, double tileWidthLon, double tileHeightLat,
                                     String stroke, double strokeWidth) {
            ensureDefs();

            // Calculate pattern size in pixels
            double patternWidth = (tileWidthLon / (maxLon - minLon)) * width;
            double patternHeight = (tileHeightLat / (maxLat - minLat)) * height;

            sb.append("<pattern id='").append(id)
                .append("' width='").append(patternWidth)
                .append("' height='").append(patternHeight)
                .append("' patternUnits='userSpaceOnUse'>\n");
            sb.append("  <rect width='").append(patternWidth)
                .append("' height='").append(patternHeight)
                .append("' fill='none' stroke='").append(stroke)
                .append("' stroke-width='").append(strokeWidth).append("'/>\n");
            sb.append("</pattern>\n");
        }

        /**
         * Draw a rectangle filled with a pattern
         */
        public void patternRect(BBox b, String patternId) {
            closeDefs(); // Ensure defs section is closed before drawing

            double X = x(b.minLon()), Y = y(b.maxLat());
            double W = x(b.maxLon()) - x(b.minLon());
            double H = y(b.minLat()) - y(b.maxLat());
            sb.append("<rect x='").append(X).append("' y='").append(Y)
                .append("' width='").append(W).append("' height='").append(H)
                .append("' fill='url(#").append(patternId).append(")'/>\n");
        }
    }
}
