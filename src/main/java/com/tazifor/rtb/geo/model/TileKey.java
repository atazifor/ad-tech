package com.tazifor.rtb.geo.model;

public record TileKey(String id) {
    @Override public String toString() { return id; }
}

