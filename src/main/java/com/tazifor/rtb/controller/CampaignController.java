package com.tazifor.rtb.controller;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.tazifor.rtb.model.Campaign;
import com.tazifor.rtb.service.BiddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;

/**
 * CampaignController - Phase 2: Campaign Management API
 *
 * PROVIDES:
 * - CRUD operations for campaigns
 * - Bulk campaign creation for testing
 * - Campaign status management
 *
 * DESIGN PHILOSOPHY:
 * This is a MANAGEMENT API, not the hot path (bidding is the hot path)
 * THEREFORE: We can afford more expensive operations here
 * - Database writes are OK (campaigns change rarely)
 * - Complex validation is OK (better to catch errors early)
 * - Detailed responses are OK (helps debugging)
 *
 * SECURITY NOTE (Important for production):
 * Phase 2 has NO authentication/authorization
 * PRODUCTION MUST ADD:
 * - API keys or OAuth tokens
 * - Role-based access control (admin vs read-only)
 * - Rate limiting to prevent abuse
 * - Audit logging of all changes
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    @Autowired
    private AerospikeClient client;

    @Autowired
    private BiddingService biddingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${aerospike.namespace}")
    private String namespace;

    private static final String CAMPAIGN_SET = "campaigns";

    /**
     * Initialize cache on startup
     *
     * @PostConstruct runs after dependency injection
     * WHY: Ensures campaigns are loaded before first bid request
     */
    @PostConstruct
    public void init() {
        biddingService.initializeCache();
    }

    /**
     * CREATE: Add new campaign
     *
     * POST /api/campaigns
     *
     * VALIDATION STRATEGY:
     * 1. Required fields check (fail fast)
     * 2. Business rules validation (e.g., budget > 0)
     * 3. ID uniqueness check (prevent duplicates)
     *
     * DESIGN DECISION: Generate ID server-side if not provided
     * WHY: Prevents collisions, ensures uniqueness
     * ALTERNATIVE: Require client to provide UUID (more control but more work)
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createCampaign(@RequestBody Campaign campaign) {
        try {
            // VALIDATION: Basic checks
            if (campaign.getName() == null || campaign.getName().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Campaign name is required"));
            }

            if (campaign.getBidPrice() != null && campaign.getBidPrice() <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Bid price must be positive"));
            }

            // GENERATE ID if not provided
            // PATTERN: UUID for globally unique identifiers
            // ALTERNATIVE: Sequential IDs (simpler but harder to distribute)
            if (campaign.getId() == null || campaign.getId().isEmpty()) {
                campaign.setId(UUID.randomUUID().toString());
            }

            // SET TIMESTAMPS
            // IMPORTANT: Always track creation time for debugging/auditing
            Instant now = Instant.now();
            campaign.setCreatedAt(now);
            campaign.setUpdatedAt(now);

            // SET DEFAULTS
            // DEFENSIVE: Ensure campaign has sensible defaults
            if (campaign.getStatus() == null) {
                campaign.setStatus(Campaign.CampaignStatus.DRAFT); // Safe default
            }
            if (campaign.getCurrentSpend() == null) {
                campaign.setCurrentSpend(0.0);
            }
            if (campaign.getTodaySpend() == null) {
                campaign.setTodaySpend(0.0);
            }
            if (campaign.getImpressions() == null) {
                campaign.setImpressions(0L);
            }
            if (campaign.getClicks() == null) {
                campaign.setClicks(0L);
            }

            // SAVE TO AEROSPIKE
            saveCampaign(campaign);

            // RETURN SUCCESS
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                    "success", true,
                    "campaignId", campaign.getId(),
                    "message", "Campaign created successfully"
                ));

        } catch (Exception e) {
            System.err.println("Error creating campaign: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create campaign: " + e.getMessage()));
        }
    }

    /**
     * READ: Get campaign by ID
     *
     * GET /api/campaigns/{id}
     *
     * DESIGN: Direct database lookup (not from cache)
     * WHY: Management API can afford 1-2ms for accurate data
     * ALTERNATIVE: Read from cache (faster but might be stale)
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCampaign(@PathVariable String id) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record record = client.get(null, key);

            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            Campaign campaign = recordToCampaign(record);
            return ResponseEntity.ok(campaign);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch campaign"));
        }
    }

    /**
     * READ ALL: List all campaigns (with optional filtering)
     *
     * GET /api/campaigns?status=ACTIVE
     *
     * SCALABILITY CONCERN:
     * Phase 2: Scan all campaigns (fine for <1000 campaigns)
     * Production: Add pagination and secondary indexes
     *
     * PERFORMANCE:
     * Scanning 1000 campaigns takes ~10ms (acceptable for management API)
     * At 10K campaigns, this becomes slow - Phase 5 will optimize
     */
    @GetMapping
    public ResponseEntity<?> listCampaigns(
        @RequestParam(required = false) String status) {
        try {
            List<Campaign> campaigns = new ArrayList<>();

            // SCAN ALL: Not efficient but simple for Phase 2
            // PRODUCTION: Use secondary index on status field
            client.scanAll(null, namespace, CAMPAIGN_SET, (key, record) -> {
                Campaign campaign = recordToCampaign(record);
                if (campaign != null) {
                    // FILTER by status if provided
                    if (status == null || campaign.getStatus().toString().equals(status)) {
                        campaigns.add(campaign);
                    }
                }
            });

            return ResponseEntity.ok(Map.of(
                "total", campaigns.size(),
                "campaigns", campaigns
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to list campaigns"));
        }
    }

    /**
     * UPDATE: Modify existing campaign
     *
     * PUT /api/campaigns/{id}
     *
     * DESIGN DECISION: Full replacement (not partial update)
     * WHY: Simpler logic, clearer semantics
     * ALTERNATIVE: PATCH for partial updates (more complex but flexible)
     *
     * IMPORTANT: Always update the updatedAt timestamp
     * WHY: Critical for cache invalidation and audit trails
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCampaign(
        @PathVariable String id,
        @RequestBody Campaign campaign) {
        try {
            // VERIFY CAMPAIGN EXISTS
            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record existing = client.get(null, key);

            if (existing == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            // FORCE ID MATCH
            // SECURITY: Prevent ID manipulation
            campaign.setId(id);

            // UPDATE TIMESTAMP
            campaign.setUpdatedAt(Instant.now());

            // SAVE UPDATED CAMPAIGN
            saveCampaign(campaign);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Campaign updated successfully"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to update campaign"));
        }
    }

    /**
     * DELETE: Remove campaign
     *
     * DELETE /api/campaigns/{id}
     *
     * DESIGN DECISION: Soft delete (change status) not hard delete
     * WHY: Preserves historical data, allows recovery from mistakes
     * PRODUCTION: Actually delete after 30 days for GDPR compliance
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCampaign(@PathVariable String id) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record record = client.get(null, key);

            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            // SOFT DELETE: Change status instead of removing
            // BENEFIT: Can recover if deleted by mistake
            Campaign campaign = recordToCampaign(record);
            campaign.setStatus(Campaign.CampaignStatus.ARCHIVED);
            campaign.setUpdatedAt(Instant.now());
            saveCampaign(campaign);

            // ALTERNATIVE: Hard delete
            // client.delete(null, key);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Campaign archived successfully"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete campaign"));
        }
    }

    /**
     * BULK CREATE: Load multiple campaigns for testing
     *
     * POST /api/campaigns/bulk
     *
     * USE CASE: Quickly set up test environment with diverse campaigns
     * This creates campaigns with different targeting profiles
     *
     * DESIGN: Generates realistic test data
     * - Different geographies (USA, UK, CA, etc.)
     * - Different device types (mobile, desktop)
     * - Different bid prices ($0.50 - $5.00)
     * - Different budgets ($100 - $10,000)
     */
    @PostMapping("/bulk")
    public ResponseEntity<?> createBulkCampaigns(@RequestParam(defaultValue = "10") int count) {
        try {
            List<String> campaignIds = new ArrayList<>();
            Random random = new Random();

            // SAMPLE DATA for realistic testing
            String[] countries = {"USA", "UK", "CA", "AU", "DE", "FR", "JP"};
            String[] devices = {"iOS", "Android", "Windows", "MacOS"};
            Integer[] deviceTypes = {1, 2}; // 1=mobile, 2=desktop

            for (int i = 0; i < count; i++) {
                // BUILD TARGETING RULES
                // REALISTIC: Each campaign targets 1-2 countries, 0-1 device types
                Campaign.TargetingRules targeting = Campaign.TargetingRules.builder()
                    .countries(Set.of(
                        countries[random.nextInt(countries.length)],
                        countries[random.nextInt(countries.length)]
                    ))
                    .deviceTypes(random.nextBoolean() ?
                        Set.of(deviceTypes[random.nextInt(deviceTypes.length)]) :
                        null)
                    .operatingSystems(random.nextBoolean() ?
                        Set.of(devices[random.nextInt(devices.length)]) :
                        null)
                    .build();

                // CREATE CAMPAIGN
                // VARIED: Different bid prices and budgets for interesting auction dynamics
                Campaign campaign = Campaign.builder()
                    .id("test-campaign-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("Test Campaign " + (i + 1))
                    .advertiserId("test-advertiser-" + (i % 3)) // 3 advertisers
                    .status(Campaign.CampaignStatus.ACTIVE)
                    .bidPrice(0.5 + random.nextDouble() * 4.5) // $0.50 - $5.00
                    .totalBudget(100.0 + random.nextDouble() * 9900) // $100 - $10K
                    .dailyBudget(10.0 + random.nextDouble() * 990) // $10 - $1K
                    .currentSpend(0.0)
                    .todaySpend(0.0)
                    .targeting(targeting)
                    .creativeId("creative-" + i)
                    .adMarkup("<div>Test Ad " + i + "</div>")
                    .advertiserDomains(List.of("example" + i + ".com"))
                    .frequencyCapImpressions(5) // 5 impressions per user
                    .frequencyCapHours(24) // per 24 hours
                    .impressions(0L)
                    .clicks(0L)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

                saveCampaign(campaign);
                campaignIds.add(campaign.getId());
            }

            // REFRESH CACHE immediately so campaigns are available for bidding
            // IMPORTANT: Without this, we'd wait up to 60 seconds for cache refresh
            biddingService.initializeCache();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "count", count,
                "campaignIds", campaignIds,
                "message", "Bulk campaigns created and cache refreshed"
            ));

        } catch (Exception e) {
            System.err.println("e = " + e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create bulk campaigns"));
        }
    }

    /**
     * UTILITY: Save campaign to Aerospike
     *
     * STORAGE STRATEGY: JSON serialization
     * WHY: Flexible schema, easy to debug
     * COST: ~0.1ms serialization overhead
     *
     * BINS STORED:
     * - data: Full JSON (for complete object retrieval)
     * - id: Campaign ID (for quick lookups)
     * - status: Status string (for secondary index - Phase 3)
     *
     * WHY MULTIPLE BINS:
     * - "data" bin: Complete object for reads
     * - "id" and "status" bins: Fast lookups without deserializing JSON
     * - TRADE-OFF: More storage but faster queries
     */
    private void saveCampaign(Campaign campaign) throws Exception {
        Key key = new Key(namespace, CAMPAIGN_SET, campaign.getId());

        // SERIALIZE to JSON
        String json = objectMapper.writeValueAsString(campaign);

        // CREATE BINS
        // AEROSPIKE CONCEPT: Bins are like columns in a row
        Bin dataBin = new Bin("data", json);
        Bin idBin = new Bin("id", campaign.getId());
        Bin statusBin = new Bin("status", campaign.getStatus().toString());
        Bin bidPriceBin = new Bin("bid_price", campaign.getEffectiveBidPrice());

        // WRITE TO AEROSPIKE
        // NULL POLICY: Use default write policy
        // PRODUCTION: Use custom policy for consistency level, TTL, etc.
        client.put(null, key, dataBin, idBin, statusBin, bidPriceBin);
    }

    /**
     * UTILITY: Convert Aerospike record to Campaign object
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
}
