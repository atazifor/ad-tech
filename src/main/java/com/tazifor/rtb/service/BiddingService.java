package com.tazifor.rtb.service;

import com.aerospike.client.*;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ScanPolicy;
import com.tazifor.rtb.model.BidRequest;
import com.tazifor.rtb.model.BidResponse;
import com.tazifor.rtb.model.Campaign;
import com.tazifor.rtb.service.BudgetService.BudgetReservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BiddingService - Phase 3: Budget-Aware Bidding
 *
 * CHANGES FROM PHASE 2:
 * 1. Budget pre-check before targeting (fast filter)
 * 2. Atomic budget reservation after campaign selection
 * 3. Campaign auto-pause on budget depletion
 * 4. Budget tracking metrics
 *
 * NEW FLOW:
 * Old: Get campaigns → Filter by targeting → Select best → Bid
 * New: Get campaigns → Pre-check budget → Filter by targeting → Select best → Reserve budget → Bid
 *                      ↑ Fast filter        ↑ Main work          ↑ Simple     ↑ CRITICAL!
 *
 * WHY PRE-CHECK BUDGET?
 * - Targeting evaluation is expensive (0.5-3ms per campaign)
 * - No point evaluating targeting if budget is depleted
 * - Budget check is cheap (0.1ms per campaign)
 * - Pre-filter saves 50-80% of targeting work
 *
 * PERFORMANCE IMPACT:
 * - Added budget pre-check: +0.1ms per campaign
 * - Atomic budget reservation: +1-2ms
 * - Total added latency: ~2-3ms
 * - Still well under 50ms target!
 */
@Service
public class BiddingService {

    @Autowired
    private AerospikeClient client;

    @Autowired
    private TargetingService targetingService;

    @Autowired
    private BudgetService budgetService;  // NEW: Budget tracking

    @Value("${aerospike.namespace}")
    private String namespace;

    private static final String CAMPAIGN_SET = "campaigns";

    @Autowired
    private ObjectMapper objectMapper;

    // Campaign cache (from Phase 2)
    private final Map<String, Campaign> campaignCache = new ConcurrentHashMap<>();
    private volatile long lastCacheRefresh = 0;
    private static final long CACHE_TTL_MS = 60_000;

    /**
     * Process bid request - Phase 3: Budget-Aware
     *
     * NEW STEPS:
     * 1. Validate request (same as Phase 2)
     * 2. Refresh cache (same as Phase 2)
     * 3. Get active campaigns (same as Phase 2)
     * 4. Pre-check budget (NEW!) → Fast filter
     * 5. Filter by targeting (same as Phase 2)
     * 6. Select best campaign (same as Phase 2)
     * 7. Reserve budget atomically (NEW!) → Guarantee no overspend
     * 8. Build bid response (same as Phase 2)
     *
     * PERFORMANCE TARGET:
     * With budget tracking: 3-7ms total (was 2-5ms in Phase 2)
     * Atomic operation adds: 1-2ms
     * Still well under 50ms RTB requirement!
     */
    public BidResponse processBidRequest(BidRequest request) {
        long startTime = System.nanoTime();

        try {
            // STEP 1: Validate request (Phase 1 logic)
            if (request.getImpressions() == null || request.getImpressions().isEmpty()) {
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.INVALID_REQUEST);
            }

            // STEP 2: Refresh cache if needed (Phase 2 logic)
            refreshCacheIfNeeded();

            // STEP 3: Get active campaigns (Phase 2 logic)
            List<Campaign> activeCampaigns = getActiveCampaigns();

            if (activeCampaigns.isEmpty()) {
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // STEP 4: PRE-CHECK BUDGET (NEW!)
            // OPTIMIZATION: Filter out campaigns with depleted budgets BEFORE targeting
            // WHY: Targeting evaluation is expensive (0.5-3ms)
            //      Budget check is cheap (0.1ms)
            //      This saves 50-80% of targeting work!
            //
            // EXAMPLE: 100 campaigns
            // - 30 have depleted budgets
            // - Without pre-check: Evaluate targeting on all 100 (50ms)
            // - With pre-check: Evaluate targeting on 70 (35ms)
            // - Savings: 15ms (30% faster!)

            List<Campaign> affordableCampaigns = activeCampaigns.stream()
                .filter(campaign -> {
                    // Fast budget check (NOT atomic, just pre-filter)
                    boolean canAfford = budgetService.canAffordBid(
                        campaign.getId(),
                        campaign.getEffectiveBidPrice()
                    );

                    if (!canAfford) {
                        // IMPORTANT: Campaign might have budget but this check failed
                        // due to race condition. That's OK! Atomic check happens later.
                        // This is just a fast filter to skip obvious cases.
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

            if (affordableCampaigns.isEmpty()) {
                // No campaigns with available budget
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // STEP 5: Filter by targeting (Phase 2 logic)
            // NOW OPERATING ON SMALLER LIST (faster!)
            List<Campaign> matchingCampaigns = affordableCampaigns.stream()
                .filter(campaign -> targetingService.matchesTargeting(campaign, request))
                .collect(Collectors.toList());

            if (matchingCampaigns.isEmpty()) {
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // STEP 6: Select best campaign (Phase 2 logic)
            Campaign selectedCampaign = selectBestCampaign(matchingCampaigns, request);

            // STEP 7: ATOMIC BUDGET RESERVATION (NEW!)
            // THIS IS THE CRITICAL STEP!
            //
            // WHY HERE: After all filtering but before responding
            // GUARANTEES: No race conditions, no overspend
            //
            // IMPORTANT: This is the ONLY place we modify budget
            // All other checks are non-modifying (safe to race)

            BudgetReservation reservation = budgetService.reserveBudget(
                selectedCampaign.getId(),
                selectedCampaign.getEffectiveBidPrice()
            );

            // Check if reservation succeeded
            if (!reservation.isSuccess()) {
                // BUDGET DEPLETED during our processing
                // This can happen if:
                // 1. Pre-check passed (budget available)
                // 2. Another request won auction and spent budget
                // 3. Now budget is depleted
                //
                // This is NORMAL at high QPS!
                // Solution: Return no-bid (we can't spend what we don't have)

                System.out.println("[BUDGET] Reservation failed for " +
                    selectedCampaign.getId() + ": " + reservation.getReason());

                // Check if campaign should be paused
                if (budgetService.shouldPauseCampaign(selectedCampaign.getId())) {
                    budgetService.pauseCampaign(
                        selectedCampaign.getId(),
                        reservation.getReason()
                    );
                }

                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // SUCCESS! Budget reserved, safe to bid

            // STEP 8: Build bid response (Phase 2 logic)
            BidResponse response = buildBidResponse(request, selectedCampaign);

            // STEP 9: Check if campaign should be paused (NEW!)
            // ASYNC: Do this check but don't block response
            // If budget is depleted, pause campaign for next request
            if (budgetService.shouldPauseCampaign(selectedCampaign.getId())) {
                // IMPORTANT: Don't block on this
                // Campaign pause can happen async
                new Thread(() -> {
                    budgetService.pauseCampaign(
                        selectedCampaign.getId(),
                        "Budget depleted"
                    );
                }).start();
            }

            // MONITORING: Track performance
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            System.out.printf(
                "[BID] Latency: %dms | Campaigns: %d active, %d affordable, %d matched | " +
                    "Winner: %s ($%.2f CPM) | New spend: $%.4f%n",
                latencyMs,
                activeCampaigns.size(),
                affordableCampaigns.size(),
                matchingCampaigns.size(),
                selectedCampaign.getId(),
                selectedCampaign.getEffectiveBidPrice(),
                reservation.getNewCurrentSpend()
            );

            return response;

        } catch (Exception e) {
            System.err.println("ERROR processing bid request: " + e.getMessage());
            e.printStackTrace();
            return BidResponse.noBid(request.getId(),
                BidResponse.NoBidReason.TECHNICAL_ERROR);
        }
    }

    /**
     * Get active campaigns from cache
     *
     * SAME AS PHASE 2 - No changes needed
     */
    private List<Campaign> getActiveCampaigns() {
        return campaignCache.values().stream()
            .filter(campaign -> campaign.getStatus() == Campaign.CampaignStatus.ACTIVE)
            .filter(Campaign::canBid)
            .collect(Collectors.toList());
    }

    /**
     * Select best campaign from matching campaigns
     *
     * SAME AS PHASE 2 - No changes needed
     *
     * FUTURE ENHANCEMENT (Phase 4):
     * Consider budget pacing in selection:
     * - If campaign is spending too fast, reduce priority
     * - If campaign is behind pace, increase priority
     */
    private Campaign selectBestCampaign(List<Campaign> campaigns, BidRequest request) {
        if (campaigns.size() == 1) {
            return campaigns.get(0);
        }

        return campaigns.stream()
            .max(Comparator.comparing(Campaign::getEffectiveBidPrice))
            .orElse(campaigns.get(0));
    }

    /**
     * Build OpenRTB bid response
     *
     * SAME AS PHASE 2 - No changes needed
     */
    private BidResponse buildBidResponse(BidRequest request, Campaign campaign) {
        List<BidResponse.SeatBid> seatBids = new ArrayList<>();
        List<BidResponse.Bid> bids = new ArrayList<>();

        BidRequest.Impression imp = request.getImpressions().get(0);

        BidResponse.Bid bid = BidResponse.Bid.builder()
            .id(UUID.randomUUID().toString())
            .impressionId(imp.getId())
            .price(campaign.getEffectiveBidPrice())
            .campaignId(campaign.getId())
            .creativeId(campaign.getCreativeId())
            .adm(campaign.getAdMarkup())
            .adomain(campaign.getAdvertiserDomains())
            .build();

        if (imp.getBanner() != null) {
            bid.setW(imp.getBanner().getW());
            bid.setH(imp.getBanner().getH());
        }

        bids.add(bid);

        BidResponse.SeatBid seatBid = BidResponse.SeatBid.builder()
            .bids(bids)
            .build();
        seatBids.add(seatBid);

        return BidResponse.builder()
            .id(request.getId())
            .seatBids(seatBids)
            .currency("USD")
            .bidid(UUID.randomUUID().toString())
            .build();
    }

    /**
     * Refresh campaign cache if TTL expired
     *
     * SAME AS PHASE 2 - No changes needed
     */
    private void refreshCacheIfNeeded() {
        long now = System.currentTimeMillis();

        if (now - lastCacheRefresh < CACHE_TTL_MS) {
            return;
        }

        synchronized (this) {
            if (now - lastCacheRefresh < CACHE_TTL_MS) {
                return;
            }

            System.out.println("[CACHE] Refreshing campaign cache...");
            loadCampaignsFromDatabase();
            lastCacheRefresh = now;
            System.out.println("[CACHE] Loaded " + campaignCache.size() + " campaigns");
        }
    }

    /**
     * Load all campaigns from Aerospike into cache
     *
     * SAME AS PHASE 2 - No changes needed
     */
    private void loadCampaignsFromDatabase() {
        try {
            ScanPolicy policy = new ScanPolicy();
            policy.maxRecords = 10000;

            campaignCache.clear();

            client.scanAll(policy, namespace, CAMPAIGN_SET, (key, record) -> {
                try {
                    Campaign campaign = recordToCampaign(record);
                    if (campaign != null) {
                        campaignCache.put(campaign.getId(), campaign);
                    }
                } catch (Exception e) {
                    System.err.println("Error loading campaign: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            System.err.println("ERROR refreshing campaign cache: " + e.getMessage());
        }
    }

    /**
     * Convert Aerospike record to Campaign object
     *
     * SAME AS PHASE 2 - No changes needed
     */
    private Campaign recordToCampaign(Record record) {
        try {
            String json = record.getString("data");
            return objectMapper.readValue(json, Campaign.class);
        } catch (Exception e) {
            System.err.println("Error parsing campaign: " + e.getMessage());
            return null;
        }
    }

    /**
     * Initialize cache on startup
     *
     * SAME AS PHASE 2 - No changes needed
     */
    public void initializeCache() {
        System.out.println("[INIT] Loading initial campaign cache...");
        loadCampaignsFromDatabase();
        lastCacheRefresh = System.currentTimeMillis();
        System.out.println("[INIT] Loaded " + campaignCache.size() + " campaigns");
    }
}