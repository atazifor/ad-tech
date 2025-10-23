package com.tazifor.rtb.geo.model;

public record LatLon(double lat, double lon) {
    public static LatLon of(double lat, double lon) { return new LatLon(lat, lon); }
}
