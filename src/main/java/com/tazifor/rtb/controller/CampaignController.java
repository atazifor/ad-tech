package com.tazifor.rtb.controller;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.tazifor.rtb.model.Campaign;
import com.tazifor.rtb.service.BiddingService;
import com.tazifor.rtb.service.BudgetService;
import com.tazifor.rtb.service.BudgetService.BudgetStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;

/**
 * CampaignController - Phase 3: Budget Management API
 *
 * NEW ENDPOINTS:
 * - GET /api/campaigns/{id}/budget - Get budget stats
 * - POST /api/campaigns/{id}/add-budget - Add more budget
 * - POST /api/campaigns/{id}/reset-daily - Reset daily spend
 *
 * SCHEDULED TASKS:
 * - Daily budget reset at midnight (automated)
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    @Autowired
    private AerospikeClient client;

    @Autowired
    private BiddingService biddingService;

    @Autowired
    private BudgetService budgetService;  // NEW: Budget management

    @Value("${aerospike.namespace}")
    private String namespace;

    private static final String CAMPAIGN_SET = "campaigns";

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        biddingService.initializeCache();
    }

    /**
     * CREATE: Add new campaign
     *
     * UPDATED FOR PHASE 3: Initialize budget fields
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createCampaign(@RequestBody Campaign campaign) {
        try {
            if (campaign.getName() == null || campaign.getName().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Campaign name is required"));
            }

            if (campaign.getBidPrice() != null && campaign.getBidPrice() <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Bid price must be positive"));
            }

            // NEW: Validate budgets
            if (campaign.getTotalBudget() != null && campaign.getTotalBudget() <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Total budget must be positive"));
            }

            if (campaign.getDailyBudget() != null && campaign.getDailyBudget() <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Daily budget must be positive"));
            }

            if (campaign.getId() == null || campaign.getId().isEmpty()) {
                campaign.setId(UUID.randomUUID().toString());
            }

            Instant now = Instant.now();
            campaign.setCreatedAt(now);
            campaign.setUpdatedAt(now);

            if (campaign.getStatus() == null) {
                campaign.setStatus(Campaign.CampaignStatus.DRAFT);
            }

            // NEW: Initialize budget fields to 0
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

            saveCampaign(campaign);

            // Refresh cache so new campaign is immediately available for bidding
            biddingService.initializeCache();

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
     * NEW: Get budget statistics for campaign
     *
     * GET /api/campaigns/{id}/budget
     *
     * RETURNS:
     * - Current spend (total)
     * - Total budget
     * - Today's spend
     * - Daily budget
     * - Remaining budget
     * - Remaining daily budget
     *
     * USE CASES:
     * - Monitoring dashboards
     * - Budget alerts
     * - Pacing analysis
     */
    @GetMapping("/{id}/budget")
    public ResponseEntity<?> getBudget(@PathVariable String id) {
        try {
            BudgetStats stats = budgetService.getBudgetStats(id);

            if (stats == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            // Calculate percentages for display
            double totalSpentPct = 0.0;
            if (stats.getTotalBudget() != null && stats.getTotalBudget() > 0) {
                totalSpentPct = (stats.getCurrentSpend() / stats.getTotalBudget()) * 100;
            }

            double dailySpentPct = 0.0;
            if (stats.getDailyBudget() != null && stats.getDailyBudget() > 0) {
                dailySpentPct = (stats.getTodaySpend() / stats.getDailyBudget()) * 100;
            }

            // Format all monetary values to 4 decimal places to avoid floating-point precision issues
            return ResponseEntity.ok(Map.of(
                "currentSpend", roundToFourDecimals(stats.getCurrentSpend()),
                "totalBudget", roundToFourDecimals(stats.getTotalBudget()),
                "remainingBudget", roundToFourDecimals(stats.getRemainingBudget()),
                "totalSpentPercentage", String.format("%.2f%%", totalSpentPct),
                "todaySpend", roundToFourDecimals(stats.getTodaySpend()),
                "dailyBudget", roundToFourDecimals(stats.getDailyBudget()),
                "remainingDailyBudget", roundToFourDecimals(stats.getRemainingDailyBudget()),
                "dailySpentPercentage", String.format("%.2f%%", dailySpentPct)
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch budget"));
        }
    }

    /**
     * NEW: Add budget to existing campaign
     *
     * POST /api/campaigns/{id}/add-budget?amount=100.00
     *
     * USE CASE: Advertiser wants to increase campaign budget mid-flight
     * IMPORTANT: This is NOT atomic with bidding (intentionally)
     * WHY: Budget increases are management operations, not time-critical
     */
    @PostMapping("/{id}/add-budget")
    public ResponseEntity<?> addBudget(
        @PathVariable String id,
        @RequestParam double amount) {
        try {
            if (amount <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Amount must be positive"));
            }

            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record record = client.get(null, key);

            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            // Get current budget
            Double currentBudget = record.getDouble("totalBudget");
            if (currentBudget == null) currentBudget = 0.0;

            // Add new budget
            double newBudget = currentBudget + amount;

            // Update campaign
            Bin budgetBin = new Bin("totalBudget", newBudget);
            client.put(null, key, budgetBin);

            // If campaign was paused due to budget, reactivate it
            String status = record.getString("status");
            if ("BUDGET_DEPLETED".equals(status)) {
                Bin statusBin = new Bin("status", "ACTIVE");
                client.put(null, key, statusBin);

                System.out.println("[BUDGET] Campaign " + id +
                    " reactivated after budget increase");
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "previousBudget", currentBudget,
                "amountAdded", amount,
                "newBudget", newBudget,
                "message", "Budget increased successfully"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to add budget"));
        }
    }

    /**
     * NEW: Reset daily spend for campaign
     *
     * POST /api/campaigns/{id}/reset-daily
     *
     * USE CASE: Manual reset for testing or special circumstances
     * NORMAL OPERATION: Automated reset runs at midnight
     */
    @PostMapping("/{id}/reset-daily")
    public ResponseEntity<?> resetDailyBudget(@PathVariable String id) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record record = client.get(null, key);

            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            // Reset today's spend to 0
            Bin todaySpendBin = new Bin("todaySpend", 0.0);
            client.put(null, key, todaySpendBin);

            System.out.println("[BUDGET] Manually reset daily spend for " + id);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Daily spend reset to $0.00"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to reset daily spend"));
        }
    }

    /**
     * SCHEDULED: Reset daily budgets at midnight
     *
     * RUNS: Every day at 00:00:00 (midnight)
     * PURPOSE: Reset todaySpend for all campaigns
     *
     * CRON EXPRESSION: "0 0 0 * * *"
     * - Second: 0
     * - Minute: 0
     * - Hour: 0
     * - Day: Every day
     * - Month: Every month
     * - Day of week: Every day
     *
     * PRODUCTION NOTE:
     * - Consider timezone handling for global campaigns
     * - May want to stagger resets to avoid load spike
     * - Add monitoring to ensure this runs successfully
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void scheduledDailyBudgetReset() {
        System.out.println("[SCHEDULED] Running daily budget reset at midnight");
        budgetService.resetDailyBudgets();
    }

    // ============================================================
    // EXISTING METHODS FROM PHASE 2 (unchanged)
    // ============================================================

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

    @GetMapping
    public ResponseEntity<?> listCampaigns(
        @RequestParam(required = false) String status) {
        try {
            List<Campaign> campaigns = new ArrayList<>();

            client.scanAll(null, namespace, CAMPAIGN_SET, (key, record) -> {
                Campaign campaign = recordToCampaign(record);
                if (campaign != null) {
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

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCampaign(
        @PathVariable String id,
        @RequestBody Campaign campaign) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record existing = client.get(null, key);

            if (existing == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            campaign.setId(id);
            campaign.setUpdatedAt(Instant.now());

            saveCampaign(campaign);

            // Refresh cache so updated campaign is immediately available for bidding
            biddingService.initializeCache();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Campaign updated successfully"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to update campaign"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCampaign(@PathVariable String id) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, id);
            Record record = client.get(null, key);

            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Campaign not found"));
            }

            Campaign campaign = recordToCampaign(record);
            campaign.setStatus(Campaign.CampaignStatus.ARCHIVED);
            campaign.setUpdatedAt(Instant.now());
            saveCampaign(campaign);

            // Refresh cache so archived campaign immediately stops bidding
            biddingService.initializeCache();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Campaign archived successfully"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete campaign"));
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> createBulkCampaigns(@RequestParam(defaultValue = "10") int count) {
        try {
            List<String> campaignIds = new ArrayList<>();
            Random random = new Random();

            String[] countries = {"USA", "UK", "CA", "AU", "DE", "FR", "JP"};
            String[] devices = {"iOS", "Android", "Windows", "MacOS"};
            Integer[] deviceTypes = {1, 2};

            for (int i = 0; i < count; i++) {
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

                // NEW: Varied budgets for testing
                double totalBudget = 100.0 + random.nextDouble() * 9900; // $100-$10K
                double dailyBudget = totalBudget / 30; // Assume 30-day campaign

                Campaign campaign = Campaign.builder()
                    .id("test-campaign-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("Test Campaign " + (i + 1))
                    .advertiserId("test-advertiser-" + (i % 3))
                    .status(Campaign.CampaignStatus.ACTIVE)
                    .bidPrice(0.5 + random.nextDouble() * 4.5) // $0.50-$5.00 CPM
                    .totalBudget(totalBudget)
                    .dailyBudget(dailyBudget)
                    .currentSpend(0.0)  // Start at 0
                    .todaySpend(0.0)    // Start at 0
                    .targeting(targeting)
                    .creativeId("creative-" + i)
                    .adMarkup("<div>Test Ad " + i + "</div>")
                    .advertiserDomains(List.of("example" + i + ".com"))
                    .frequencyCapImpressions(5)
                    .frequencyCapHours(24)
                    .impressions(0L)
                    .clicks(0L)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

                saveCampaign(campaign);
                campaignIds.add(campaign.getId());
            }

            biddingService.initializeCache();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "count", count,
                "campaignIds", campaignIds,
                "message", "Bulk campaigns created with budget tracking"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create bulk campaigns"));
        }
    }

    private void saveCampaign(Campaign campaign) throws Exception {
        Key key = new Key(namespace, CAMPAIGN_SET, campaign.getId());

        String json = objectMapper.writeValueAsString(campaign);

        Bin dataBin = new Bin("data", json);
        Bin idBin = new Bin("id", campaign.getId());
        Bin statusBin = new Bin("status", campaign.getStatus().toString());
        Bin bidPriceBin = new Bin("bid_price", campaign.getEffectiveBidPrice());

        // NEW: Store budget fields separately for fast access
        Bin currentSpendBin = new Bin("currentSpend",
            campaign.getCurrentSpend() != null ? campaign.getCurrentSpend() : 0.0);
        Bin totalBudgetBin = new Bin("totalBudget",
            campaign.getTotalBudget() != null ? campaign.getTotalBudget() : 0.0);
        Bin todaySpendBin = new Bin("todaySpend",
            campaign.getTodaySpend() != null ? campaign.getTodaySpend() : 0.0);
        Bin dailyBudgetBin = new Bin("dailyBudget",
            campaign.getDailyBudget() != null ? campaign.getDailyBudget() : 0.0);

        client.put(null, key, dataBin, idBin, statusBin, bidPriceBin,
            currentSpendBin, totalBudgetBin, todaySpendBin, dailyBudgetBin);
    }

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
     * Round monetary value to 4 decimal places
     *
     * WHY 4 DECIMALS:
     * - CPM pricing goes down to $0.0001 per impression
     * - Example: $2.50 CPM = $0.0025 per impression
     * - Need 4 decimals to track sub-cent precision
     *
     * FLOATING POINT FIX:
     * - Without rounding: 0.9950000000000008 (ugly!)
     * - With rounding: 0.9950 (clean!)
     */
    private Double roundToFourDecimals(Double value) {
        if (value == null) return null;
        return Math.round(value * 10000.0) / 10000.0;
    }
}