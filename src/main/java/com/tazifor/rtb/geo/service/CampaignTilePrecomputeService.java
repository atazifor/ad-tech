package com.tazifor.rtb.geo.service;

import com.tazifor.rtb.geo.model.LatLon;
import com.tazifor.rtb.geo.model.Polygon;
import com.tazifor.rtb.geo.model.TileKey;
import com.tazifor.rtb.geo.spi.H3Tiler;
import com.tazifor.rtb.geo.spi.RectGridTiler;
import com.tazifor.rtb.geo.spi.S2Tiler;
import com.tazifor.rtb.geo.spi.Tiler;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CampaignTilePrecomputeService {
    private final Map<String, Set<TileKey>> allowedByCampaign = new ConcurrentHashMap<>();
    private volatile Tiler tiler = new RectGridTiler(0.01, 0.01); // ~1.1km lat step

    public void useRectGrid(double dLat, double dLon) {
        this.tiler = new RectGridTiler(dLat, dLon);
        // NOTE: callers should re-run loadCampaign after swapping tiler
    }

    public void useH3(int resolution) {
        this.tiler = new H3Tiler(resolution);
        // NOTE: callers should re-run loadCampaign after swapping tiler
    }

    public void useS2(int resolution) {
        this.tiler = new S2Tiler(resolution);
        // NOTE: callers should re-run loadCampaign after swapping tiler
    }

    public String tilerName() { return tiler.name(); }

    public void loadCampaign(String campaignId, Polygon polygon, double coverageThresholdPercent) {
        allowedByCampaign.put(campaignId, tiler.tilesForPolygon(polygon, coverageThresholdPercent));
    }

    public boolean allows(String campaignId, LatLon p) {
        Set<TileKey> set = allowedByCampaign.get(campaignId);
        if (set == null) return false;
        return set.contains(tiler.tileOf(p));
    }

    public Set<TileKey> getAllowed(String campaignId) {
        return allowedByCampaign.getOrDefault(campaignId, Set.of());
    }

    public Tiler tiler() { return tiler; }
}
