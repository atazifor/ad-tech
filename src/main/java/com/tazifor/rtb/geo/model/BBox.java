package com.tazifor.rtb.geo.model;

public record BBox(double minLat, double minLon, double maxLat, double maxLon) {
    public boolean contains(LatLon p) {
        return p.lat() >= minLat && p.lat() <= maxLat && p.lon() >= minLon && p.lon() <= maxLon;
    }
}
