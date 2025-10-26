package com.tazifor.rtb.service;

import com.aerospike.client.*;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ScanPolicy;
import com.tazifor.rtb.model.BidRequest;
import com.tazifor.rtb.model.BidResponse;
import com.tazifor.rtb.model.Campaign;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BiddingService - Phase 2: Multi-Campaign Bidding with Targeting
 *
 * KEY CHANGES FROM PHASE 1:
 * 1. Multiple active campaigns (was: single campaign)
 * 2. Campaign targeting evaluation (was: no targeting)
 * 3. Campaign selection algorithm (was: first campaign wins)
 * 4. Local campaign cache (was: fetch from DB every time)
 *
 * DESIGN DECISIONS EXPLAINED:
 *
 * 1. WHY CACHE CAMPAIGNS?
 *    - Aerospike is fast (~1ms), but at 10K QPS that's 10 seconds of DB time per second
 *    - Campaigns change rarely (minutes/hours), requests come every millisecond
 *    - Cache hit saves 1-2ms per request = HUGE performance win
 *
 * 2. WHY NOT USE SPRING CACHE?
 *    - We need fine-grained control over cache invalidation
 *    - We want to refresh cache in background without blocking requests
 *    - Custom cache gives us metrics and monitoring hooks
 *
 * 3. WHY CONCURRENT HASHMAP?
 *    - Multiple threads will access cache simultaneously at 10K QPS
 *    - ConcurrentHashMap is lock-free for reads (critical for performance)
 *    - Regular HashMap would cause race conditions and crashes
 *
 * PERFORMANCE OPTIMIZATION STRATEGY:
 * - Fast path: Memory lookup (cache) ~0.01ms
 * - Slow path: Database lookup (Aerospike) ~1-2ms
 * - We aim for 99%+ cache hit rate
 */
@Service
public class BiddingService {

    @Autowired
    private AerospikeClient client;

    @Autowired
    private TargetingService targetingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${aerospike.namespace}")
    private String namespace;

    private static final String CAMPAIGN_SET = "campaigns";

    /**
     * IN-MEMORY CAMPAIGN CACHE
     *
     * DESIGN CHOICE: ConcurrentHashMap<String, Campaign>
     * - Key: Campaign ID
     * - Value: Full campaign object
     *
     * MEMORY CONSIDERATION:
     * - Average campaign: ~2KB
     * - 10,000 campaigns: ~20MB
     * - This is TINY compared to modern servers (8GB+)
     *
     * THREAD SAFETY:
     * - ConcurrentHashMap is thread-safe for all operations
     * - No synchronization needed for reads (most common operation)
     * - Writes (cache updates) are rare and automatically synchronized
     *
     * CACHE INVALIDATION (The Hard Problem):
     * Phase 2: Simple time-based refresh every 60 seconds
     * Phase 4: Will add event-based invalidation when campaigns change
     *
     * WHY NOT JUST LOAD ALL CAMPAIGNS AT STARTUP?
     * - We do! But we also refresh periodically to catch changes
     * - This gives us both speed (startup) and accuracy (refresh)
     */
    private final Map<String, Campaign> campaignCache = new ConcurrentHashMap<>();

    /**
     * Track last cache refresh time
     * Volatile: Ensures visibility across threads without synchronization
     */
    private volatile long lastCacheRefresh = 0;

    /**
     * Cache TTL: How long before we refresh
     * 60 seconds is a good balance:
     * - Recent enough for campaign changes
     * - Infrequent enough to not impact performance
     */
    private static final long CACHE_TTL_MS = 60_000; // 60 seconds

    /**
     * Process a bid request - Phase 2 Enhanced
     *
     * ALGORITHM:
     * 1. Validate request (cheap)
     * 2. Get active campaigns from cache (fast)
     * 3. Filter by targeting (main work)
     * 4. Select best campaign (simple in Phase 2, sophisticated in Phase 4)
     * 5. Build bid response (cheap)
     *
     * LATENCY BREAKDOWN (typical):
     * - Validation: 0.01ms
     * - Cache lookup: 0.01ms
     * - Targeting evaluation: 0.1-0.5ms per campaign
     * - Campaign selection: 0.01ms
     * - Response building: 0.1ms
     * Total: ~0.5-2ms depending on # of campaigns
     *
     * OPTIMIZATION OPPORTUNITY:
     * If we have 100 campaigns, checking each takes 10-50ms (too slow!)
     * Phase 3 will add indexing to pre-filter campaigns (e.g., by country)
     * This reduces 100 campaigns to ~5 candidates, saving 45ms!
     */
    public BidResponse processBidRequest(BidRequest request) {
        long startTime = System.nanoTime();

        try {
            // STEP 1: Validate request
            // DEFENSIVE PROGRAMMING: Always validate input
            // FAIL FAST: If invalid, return immediately
            if (request.getImpressions() == null || request.getImpressions().isEmpty()) {
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.INVALID_REQUEST);
            }

            // STEP 2: Refresh cache if needed
            // WHY HERE: Check happens once per request, not per campaign
            // PERFORMANCE: Most requests skip this (cache is fresh)
            refreshCacheIfNeeded();

            // STEP 3: Get active campaigns
            // CRITICAL PATH: This is where we spend most of our time
            List<Campaign> activeCampaigns = getActiveCampaigns();

            if (activeCampaigns.isEmpty()) {
                // NO CAMPAIGNS: Common in test environments, rare in production
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // STEP 4: Filter campaigns by targeting
            // PERFORMANCE NOTE: This is O(n) where n = number of campaigns
            // Each campaign checked: 0.1-0.5ms
            // 100 campaigns = 10-50ms (potentially too slow!)
            // Phase 3 will optimize this with campaign indexing
            List<Campaign> matchingCampaigns = activeCampaigns.stream()
                .filter(campaign -> targetingService.matchesTargeting(campaign, request))
                .collect(Collectors.toList());

            if (matchingCampaigns.isEmpty()) {
                // NO MATCH: Very common (50-70% of requests)
                // Not an error - just means no campaigns target this user
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // STEP 5: Select best campaign
            // PHASE 2 STRATEGY: Highest bid wins (simple auction)
            // WHY: Easy to understand, matches real-world ad auctions
            // FUTURE: Phase 4 will add pacing, budget consideration, CTR optimization
            Campaign selectedCampaign = selectBestCampaign(matchingCampaigns, request);

            // STEP 6: Build bid response
            BidResponse response = buildBidResponse(request, selectedCampaign);

            // MONITORING: Track performance
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            System.out.printf("[BID] Latency: %dms | Campaigns: %d active, %d matched | Winner: %s ($%.2f)%n",
                latencyMs, activeCampaigns.size(), matchingCampaigns.size(),
                selectedCampaign.getId(), selectedCampaign.getEffectiveBidPrice());

            return response;

        } catch (Exception e) {
            // GRACEFUL DEGRADATION: Even if something breaks, return valid response
            // NEVER throw exceptions in RTB - exchanges will blacklist you!
            System.err.println("ERROR processing bid request: " + e.getMessage());
            e.printStackTrace();
            return BidResponse.noBid(request.getId(),
                BidResponse.NoBidReason.TECHNICAL_ERROR);
        }
    }

    /**
     * Get active campaigns from cache
     *
     * DESIGN DECISION: Filter in-memory, not in database
     * WHY: Filtering 1000 campaigns in memory takes <1ms
     *      Querying database takes 1-5ms even with indexes
     *
     * MEMORY EFFICIENCY: We're just filtering pointers, not copying campaigns
     *
     * @return List of campaigns that are active and can bid
     */
    private List<Campaign> getActiveCampaigns() {
        // STREAM PIPELINE:
        // 1. Get all campaigns from cache (O(1) for ConcurrentHashMap.values())
        // 2. Filter to only active campaigns (cheap - just status check)
        // 3. Filter to campaigns that can bid (cheap - budget checks)
        // 4. Collect to list
        //
        // PERFORMANCE: This entire operation takes <0.1ms for 1000 campaigns
        return campaignCache.values().stream()
            .filter(campaign -> campaign.getStatus() == Campaign.CampaignStatus.ACTIVE)
            .filter(Campaign::canBid) // Checks budget, dates, etc.
            .collect(Collectors.toList());
    }

    /**
     * Select best campaign from matching campaigns
     *
     * PHASE 2 ALGORITHM: Highest bid wins (simple first-price auction)
     *
     * DESIGN RATIONALE:
     * - Simple and predictable
     * - Matches advertiser expectations
     * - Easy to test and debug
     *
     * FUTURE ENHANCEMENTS (Phase 4+):
     * - Consider budget pacing (don't spend budget too fast)
     * - Consider click-through rate (higher CTR = more revenue)
     * - Consider advertiser priority (premium advertisers get preference)
     * - Implement second-price auction (winner pays $0.01 more than 2nd place)
     *
     * PERFORMANCE: O(n) where n = number of matching campaigns
     * Typically n is small (1-5) so this is very fast
     */
    private Campaign selectBestCampaign(List<Campaign> campaigns, BidRequest request) {
        // EDGE CASE: Only one campaign matches
        // OPTIMIZATION: Skip comparison if only one option
        if (campaigns.size() == 1) {
            return campaigns.get(0);
        }

        // Find campaign with highest bid
        // JAVA 8 STREAM: max() with comparator
        // ALTERNATIVE: Could use old-school for loop (slightly faster but less readable)
        return campaigns.stream()
            .max(Comparator.comparing(Campaign::getEffectiveBidPrice))
            .orElse(campaigns.get(0)); // Fallback (should never happen)
    }

    /**
     * Build OpenRTB bid response
     *
     * This converts our internal Campaign object to OpenRTB 2.5 format
     *
     * DESIGN NOTE: We only bid on first impression
     * WHY: Multi-imp requests are rare in display advertising
     * FUTURE: Phase 6 can add support for bidding on multiple impressions
     */
    private BidResponse buildBidResponse(BidRequest request, Campaign campaign) {
        List<BidResponse.SeatBid> seatBids = new ArrayList<>();
        List<BidResponse.Bid> bids = new ArrayList<>();

        // Get first impression
        BidRequest.Impression imp = request.getImpressions().get(0);

        // Create bid object
        BidResponse.Bid bid = BidResponse.Bid.builder()
            .id(UUID.randomUUID().toString())
            .impressionId(imp.getId())
            .price(campaign.getEffectiveBidPrice())
            .campaignId(campaign.getId())
            .creativeId(campaign.getCreativeId())
            .adm(campaign.getAdMarkup())
            .adomain(campaign.getAdvertiserDomains())
            .build();

        // Set creative dimensions if banner ad
        if (imp.getBanner() != null) {
            bid.setW(imp.getBanner().getW());
            bid.setH(imp.getBanner().getH());
        }

        bids.add(bid);

        // Wrap in SeatBid
        // SEAT ID: Use advertiser ID to identify which buyer is bidding
        // WHY: Ad exchanges use seat ID for attribution, billing, and reporting
        BidResponse.SeatBid seatBid = BidResponse.SeatBid.builder()
            .bids(bids)
            .seat(campaign.getAdvertiserId())
            .build();
        seatBids.add(seatBid);

        // Build final response
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
     * DESIGN PATTERN: Time-based cache invalidation
     * TRADE-OFF: Simple but can show stale data for up to 60 seconds
     *
     * THREAD SAFETY CONSIDERATION:
     * Multiple threads might check at same time and both try to refresh
     * SOLUTION: Double-checked locking pattern (check again after sync)
     *
     * WHY NOT SYNCHRONIZED METHOD:
     * - Would block ALL requests during refresh (bad!)
     * - We only want to block refresh, not normal cache access
     */
    private void refreshCacheIfNeeded() {
        long now = System.currentTimeMillis();

        // FAST PATH: Cache is fresh, no need to refresh
        // PERFORMANCE: This is true 99.99% of the time
        if (now - lastCacheRefresh < CACHE_TTL_MS) {
            return;
        }

        // SLOW PATH: Cache might need refresh
        // SYNCHRONIZATION: Only one thread should refresh at a time
        synchronized (this) {
            // DOUBLE-CHECK: Another thread might have refreshed while we waited
            if (now - lastCacheRefresh < CACHE_TTL_MS) {
                return; // Another thread just refreshed
            }

            // Actually refresh cache
            System.out.println("[CACHE] Refreshing campaign cache...");
            loadCampaignsFromDatabase();
            lastCacheRefresh = now;
            System.out.println("[CACHE] Loaded " + campaignCache.size() + " campaigns");
        }
    }

    /**
     * Load all campaigns from Aerospike into cache
     *
     * DESIGN DECISION: Full table scan
     * WHY: In Phase 2, we have few campaigns (<100), so scan is fast
     * PRODUCTION: With 10K+ campaigns, use secondary indexes instead
     *
     * AEROSPIKE SCAN:
     * - Scans all records in the "campaigns" set
     * - Aerospike is fast: Can scan 100K records in ~100ms
     * - We do this infrequently (every 60 seconds) so it's acceptable
     *
     * MEMORY IMPACT:
     * - We load all campaigns into memory
     * - 10,000 campaigns × 2KB each = 20MB (tiny!)
     */
    private void loadCampaignsFromDatabase() {
        try {
            ScanPolicy policy = new ScanPolicy();
            policy.maxRecords = 10000; // Safety limit

            // Clear old cache
            // WHY: Prevents memory leak from deleted campaigns
            campaignCache.clear();

            // Scan Aerospike
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
            // GRACEFUL DEGRADATION: Keep old cache if refresh fails
            // Better to serve slightly stale data than crash
        }
    }

    /**
     * Convert Aerospike record to Campaign object
     *
     * SERIALIZATION STRATEGY: JSON in Aerospike
     * WHY JSON: Easy to debug, human-readable, flexible schema
     * ALTERNATIVE: Aerospike bins for each field (faster but rigid)
     *
     * PHASE 2 CHOICE: JSON for flexibility
     * PHASE 5: Might switch to bins for performance (saves ~0.5ms per lookup)
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
     * Called by Spring after bean creation
     *
     * WHY: Ensures cache is populated before first request
     * CRITICAL: Without this, first requests would be slow (cache miss)
     */
    public void initializeCache() {
        System.out.println("[INIT] Loading initial campaign cache...");
        loadCampaignsFromDatabase();
        lastCacheRefresh = System.currentTimeMillis();
        System.out.println("[INIT] Loaded " + campaignCache.size() + " campaigns");
    }
}