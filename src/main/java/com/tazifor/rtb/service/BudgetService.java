package com.tazifor.rtb.service;

import com.aerospike.client.*;
import com.aerospike.client.Record;
import com.aerospike.client.policy.WritePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * BudgetService - Phase 3: Atomic Budget Tracking
 *
 * THE CORE PROBLEM WE'RE SOLVING:
 * At 10,000 QPS, multiple threads are trying to spend from the same campaign budget
 * simultaneously. Without atomic operations, we get RACE CONDITIONS:
 *
 * Thread 1: Read budget=$99.50, bid=$0.50 → Check: $100 ✓ → Write $100
 * Thread 2: Read budget=$99.50, bid=$0.50 → Check: $100 ✓ → Write $100
 * Result: Both wrote $100, but we actually spent $100.50!
 *
 * THE SOLUTION:
 * Aerospike's atomic operations guarantee that the read-modify-write happens
 * as a SINGLE TRANSACTION. No other thread can interfere.
 *
 * AEROSPIKE OPERATIONS:
 * - Operation.add(): Atomic increment (like SQL's UPDATE x = x + 1)
 * - Operation.get(): Read value after modification
 * - operate(): Executes multiple operations atomically
 *
 * WHY THIS MATTERS:
 * Without atomic operations, advertisers could overspend by 10-20% at high QPS.
 * That's real money! A $100K campaign could overspend by $20K!
 *
 * PERFORMANCE NOTE:
 * Atomic operations in Aerospike are FAST (~1-2ms)
 * Much faster than database transactions (~5-50ms)
 * Still fast enough for our <50ms RTB requirement
 */
@Service
public class BudgetService {

    @Autowired
    private AerospikeClient client;

    @Autowired
    @Qualifier("budgetWritePolicy")
    private WritePolicy budgetWritePolicy;

    @Value("${aerospike.namespace}")
    private String namespace;

    private static final String CAMPAIGN_SET = "campaigns";

    /**
     * Check if campaign can afford to bid (WITHOUT modifying budget)
     *
     * DESIGN DECISION: Fast pre-check before expensive operations
     * WHY: If budget is clearly depleted, skip targeting evaluation
     *
     * RACE CONDITION WARNING:
     * This check is NOT atomic. Between check and actual bid, budget could deplete.
     * That's OK! We do atomic check again in reserveBudget().
     *
     * THINK OF IT AS:
     * - canAffordBid(): Quick filter (95% accurate)
     * - reserveBudget(): Authoritative check (100% accurate, atomic)
     *
     * @param campaignId Campaign to check
     * @param bidPrice CPM bid price (e.g., $2.50)
     * @return true if campaign likely has budget (not guaranteed!)
     */
    public boolean canAffordBid(String campaignId, double bidPrice) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, campaignId);

            // READ current spend values
            // NOTE: This is a snapshot, not locked. Values can change immediately after.
            Record record = client.get(null, key,
                "currentSpend", "totalBudget", "todaySpend", "dailyBudget");

            if (record == null) {
                return false; // Campaign doesn't exist
            }

            // EXTRACT budget information
            Double currentSpend = record.getDouble("currentSpend");
            Double totalBudget = record.getDouble("totalBudget");
            Double todaySpend = record.getDouble("todaySpend");
            Double dailyBudget = record.getDouble("dailyBudget");

            // CONVERT CPM to cost per single impression
            // Example: $2.50 CPM → $0.0025 per impression
            double costPerImpression = bidPrice / 1000.0;

            // CHECK TOTAL BUDGET
            // If campaign has total budget limit
            if (totalBudget != null && totalBudget > 0) {
                if (currentSpend == null) currentSpend = 0.0;

                // PROJECTION: If we win this bid, will we exceed budget?
                double projectedSpend = currentSpend + costPerImpression;

                if (projectedSpend > totalBudget) {
                    return false; // Would exceed total budget
                }
            }

            // CHECK DAILY BUDGET
            // If campaign has daily budget limit
            if (dailyBudget != null && dailyBudget > 0) {
                if (todaySpend == null) todaySpend = 0.0;

                // PROJECTION: If we win today's bid, will we exceed daily budget?
                double projectedDailySpend = todaySpend + costPerImpression;

                if (projectedDailySpend > dailyBudget) {
                    return false; // Would exceed daily budget
                }
            }

            return true; // Looks like we can afford it!

        } catch (Exception e) {
            System.err.println("Error checking budget: " + e.getMessage());
            // FAIL SAFE: If we can't check, don't bid
            return false;
        }
    }

    /**
     * Atomically reserve budget for a bid (if available)
     *
     * THIS IS THE CRITICAL METHOD!
     * This is where race conditions are prevented using atomic operations.
     *
     * ATOMICITY GUARANTEE:
     * All these operations happen as ONE INDIVISIBLE TRANSACTION:
     * 1. Read current spend
     * 2. Add bid cost
     * 3. Write new spend
     * 4. Return result
     *
     * NO OTHER THREAD can interfere between these steps!
     *
     * HOW AEROSPIKE ENSURES ATOMICITY:
     * - Single master per partition (no distributed coordination needed)
     * - Operations execute on master node
     * - Master locks the record during operation
     * - Other operations queue up (wait their turn)
     *
     * PERFORMANCE:
     * - Atomic operation: ~1-2ms
     * - Database transaction: ~5-50ms
     * - Aerospike is 5-50x faster!
     *
     * @param campaignId Campaign to charge
     * @param bidPrice CPM bid price (e.g., $2.50)
     * @return BudgetReservation with success status and final spend
     */
    public BudgetReservation reserveBudget(String campaignId, double bidPrice) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, campaignId);

            // CONVERT CPM to cost per impression
            double costPerImpression = bidPrice / 1000.0;

            // STEP 1: Read current budget state (for validation)
            // We need this BEFORE the atomic operation to check limits
            Record currentRecord = client.get(null, key,
                "currentSpend", "totalBudget", "todaySpend", "dailyBudget");

            if (currentRecord == null) {
                return BudgetReservation.failed("Campaign not found");
            }

            Double currentSpend = currentRecord.getDouble("currentSpend");
            Double totalBudget = currentRecord.getDouble("totalBudget");
            Double todaySpend = currentRecord.getDouble("todaySpend");
            Double dailyBudget = currentRecord.getDouble("dailyBudget");

            // Initialize if null
            if (currentSpend == null) currentSpend = 0.0;
            if (todaySpend == null) todaySpend = 0.0;

            // STEP 2: Validate budget BEFORE atomic operation
            // WHY: Atomic operations are expensive (1-2ms), skip if we know it'll fail

            // Check total budget
            if (totalBudget != null && totalBudget > 0) {
                double projectedSpend = currentSpend + costPerImpression;
                if (projectedSpend > totalBudget) {
                    return BudgetReservation.failed("Total budget exceeded");
                }
            }

            // Check daily budget
            if (dailyBudget != null && dailyBudget > 0) {
                double projectedDailySpend = todaySpend + costPerImpression;
                if (projectedDailySpend > dailyBudget) {
                    return BudgetReservation.failed("Daily budget exceeded");
                }
            }

            // STEP 3: ATOMIC OPERATION - The Magic Happens Here!
            //
            // CRITICAL: This entire block executes atomically on the Aerospike server
            // No other operation can read/write this record while this executes
            //
            // Think of it like:
            // BEGIN TRANSACTION
            //   currentSpend = currentSpend + costPerImpression
            //   todaySpend = todaySpend + costPerImpression
            //   RETURN currentSpend, todaySpend
            // COMMIT TRANSACTION
            //
            // But much faster than SQL transactions!

            Record result = client.operate(
                budgetWritePolicy,  // Use special policy for strong consistency
                key,
                // ADD operations are ATOMIC increments
                // Like SQL: UPDATE campaigns SET currentSpend = currentSpend + 0.0025
                Operation.add(new Bin("currentSpend", costPerImpression)),
                Operation.add(new Bin("todaySpend", costPerImpression)),

                // GET operations return the NEW value (after addition)
                Operation.get("currentSpend"),
                Operation.get("todaySpend"),
                Operation.get("totalBudget"),
                Operation.get("dailyBudget")
            );

            // STEP 4: Extract results from atomic operation
            Double newCurrentSpend = result.getDouble("currentSpend");
            Double newTodaySpend = result.getDouble("todaySpend");
            Double readTotalBudget = result.getDouble("totalBudget");
            Double readDailyBudget = result.getDouble("dailyBudget");

            // STEP 5: DOUBLE CHECK - Did we overspend?
            // This can happen if another thread reserved budget between our pre-check
            // and this atomic operation
            //
            // SCENARIO:
            // Thread 1: Pre-check: $99.50 + $0.50 = $100 ✓
            // Thread 2: Atomically adds $0.50 → $100.00
            // Thread 1: Atomically adds $0.50 → $100.50 ❌
            // Thread 1: Detects overspend HERE, refunds

            boolean overTotal = readTotalBudget != null &&
                readTotalBudget > 0 &&
                newCurrentSpend > readTotalBudget;

            boolean overDaily = readDailyBudget != null &&
                readDailyBudget > 0 &&
                newTodaySpend > readDailyBudget;

            if (overTotal || overDaily) {
                // ROLLBACK: We overspent, refund atomically
                // Subtract the cost we just added
                client.operate(
                    budgetWritePolicy,
                    key,
                    Operation.add(new Bin("currentSpend", -costPerImpression)),
                    Operation.add(new Bin("todaySpend", -costPerImpression))
                );

                String reason = overTotal ? "Total budget exceeded" : "Daily budget exceeded";
                return BudgetReservation.failed(reason);
            }

            // SUCCESS! Budget reserved atomically
            return BudgetReservation.success(newCurrentSpend, newTodaySpend);

        } catch (Exception e) {
            System.err.println("Error reserving budget: " + e.getMessage());
            e.printStackTrace();
            return BudgetReservation.failed("Technical error: " + e.getMessage());
        }
    }

    /**
     * Check if campaign should be paused due to budget depletion
     *
     * CALLED AFTER: reserveBudget() succeeds
     * PURPOSE: Determine if campaign should stop bidding
     *
     * DESIGN PATTERN: Separate check from budget reservation
     * WHY: Budget reservation is in hot path (must be fast)
     *      Status updates can happen async (background thread)
     *
     * THRESHOLD:
     * We use 99% instead of 100% to handle floating point precision issues
     * Example: Budget $100.00, Spend $99.9999999 → Close enough, pause!
     */
    public boolean shouldPauseCampaign(String campaignId) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, campaignId);
            Record record = client.get(null, key,
                "currentSpend", "totalBudget", "todaySpend", "dailyBudget");

            if (record == null) return false;

            Double currentSpend = record.getDouble("currentSpend");
            Double totalBudget = record.getDouble("totalBudget");
            Double todaySpend = record.getDouble("todaySpend");
            Double dailyBudget = record.getDouble("dailyBudget");

            // Check total budget (99% threshold for safety)
            if (totalBudget != null && totalBudget > 0 && currentSpend != null) {
                if (currentSpend >= totalBudget * 0.99) {
                    return true; // Total budget essentially depleted
                }
            }

            // Check daily budget
            if (dailyBudget != null && dailyBudget > 0 && todaySpend != null) {
                if (todaySpend >= dailyBudget * 0.99) {
                    return true; // Daily budget essentially depleted
                }
            }

            return false;

        } catch (Exception e) {
            System.err.println("Error checking pause status: " + e.getMessage());
            return false; // Don't pause on error
        }
    }

    /**
     * Pause campaign due to budget depletion
     *
     * CALLED BY: Background thread or after winning auction
     * NOT IN HOT PATH: Can afford 2-5ms operation
     *
     * UPDATES:
     * 1. Campaign status → BUDGET_DEPLETED
     * 2. Cache invalidation (so bidding stops immediately)
     */
    public void pauseCampaign(String campaignId, String reason) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, campaignId);

            // Update status bin
            Bin statusBin = new Bin("status", "BUDGET_DEPLETED");
            Bin reasonBin = new Bin("pause_reason", reason);

            client.put(null, key, statusBin, reasonBin);

            System.out.println("[BUDGET] Campaign " + campaignId +
                " paused: " + reason);

        } catch (Exception e) {
            System.err.println("Error pausing campaign: " + e.getMessage());
        }
    }

    /**
     * Reset daily spend counters (run at midnight)
     *
     * SCHEDULING: Called by scheduled task every day at midnight
     * PURPOSE: Reset todaySpend to 0 for all campaigns
     *
     * PRODUCTION TIP:
     * - Run this in a background thread (not during peak traffic)
     * - Batch process campaigns (100 at a time)
     * - Handle timezone differences for global campaigns
     *
     * PHASE 3 IMPLEMENTATION: Simple version
     * PHASE 6: Will add sophisticated scheduling
     */
    public void resetDailyBudgets() {
        System.out.println("[BUDGET] Resetting daily budgets...");

        try {
            // Scan all campaigns
            client.scanAll(null, namespace, CAMPAIGN_SET, (key, record) -> {
                try {
                    // Reset todaySpend to 0 atomically
                    // NOTE: We use put() not operate() because we're setting to 0
                    Bin todaySpendBin = new Bin("todaySpend", 0.0);
                    client.put(null, key, todaySpendBin);

                } catch (Exception e) {
                    System.err.println("Error resetting daily budget for " +
                        key.userKey + ": " + e.getMessage());
                }
            });

            System.out.println("[BUDGET] Daily budgets reset complete");

        } catch (Exception e) {
            System.err.println("Error resetting daily budgets: " + e.getMessage());
        }
    }

    /**
     * Get budget statistics for monitoring
     *
     * USED FOR: Dashboards, alerts, debugging
     * NOT IN HOT PATH: Can afford slower operation
     */
    public BudgetStats getBudgetStats(String campaignId) {
        try {
            Key key = new Key(namespace, CAMPAIGN_SET, campaignId);
            Record record = client.get(null, key,
                "currentSpend", "totalBudget", "todaySpend", "dailyBudget");

            if (record == null) return null;

            return BudgetStats.builder()
                .currentSpend(record.getDouble("currentSpend"))
                .totalBudget(record.getDouble("totalBudget"))
                .todaySpend(record.getDouble("todaySpend"))
                .dailyBudget(record.getDouble("dailyBudget"))
                .remainingBudget(calculateRemaining(
                    record.getDouble("currentSpend"),
                    record.getDouble("totalBudget")))
                .remainingDailyBudget(calculateRemaining(
                    record.getDouble("todaySpend"),
                    record.getDouble("dailyBudget")))
                .build();

        } catch (Exception e) {
            System.err.println("Error getting budget stats: " + e.getMessage());
            return null;
        }
    }

    /**
     * Helper: Calculate remaining budget
     */
    private Double calculateRemaining(Double spent, Double budget) {
        if (budget == null || budget <= 0) return null;
        if (spent == null) spent = 0.0;
        return Math.max(0, budget - spent);
    }

    /**
     * Budget Reservation Result
     *
     * IMMUTABLE: Thread-safe, can be passed between threads
     * CONTAINS: Success status and updated spend values
     */
    public static class BudgetReservation {
        private final boolean success;
        private final String reason;
        private final Double newCurrentSpend;
        private final Double newTodaySpend;

        private BudgetReservation(boolean success, String reason,
                                  Double newCurrentSpend, Double newTodaySpend) {
            this.success = success;
            this.reason = reason;
            this.newCurrentSpend = newCurrentSpend;
            this.newTodaySpend = newTodaySpend;
        }

        public static BudgetReservation success(Double currentSpend, Double todaySpend) {
            return new BudgetReservation(true, null, currentSpend, todaySpend);
        }

        public static BudgetReservation failed(String reason) {
            return new BudgetReservation(false, reason, null, null);
        }

        public boolean isSuccess() { return success; }
        public String getReason() { return reason; }
        public Double getNewCurrentSpend() { return newCurrentSpend; }
        public Double getNewTodaySpend() { return newTodaySpend; }
    }

    /**
     * Budget Statistics DTO
     *
     * USED FOR: Monitoring, dashboards, analytics
     */
    public static class BudgetStats {
        private Double currentSpend;
        private Double totalBudget;
        private Double todaySpend;
        private Double dailyBudget;
        private Double remainingBudget;
        private Double remainingDailyBudget;

        public static Builder builder() { return new Builder(); }

        // Getters
        public Double getCurrentSpend() { return currentSpend; }
        public Double getTotalBudget() { return totalBudget; }
        public Double getTodaySpend() { return todaySpend; }
        public Double getDailyBudget() { return dailyBudget; }
        public Double getRemainingBudget() { return remainingBudget; }
        public Double getRemainingDailyBudget() { return remainingDailyBudget; }

        public static class Builder {
            private BudgetStats stats = new BudgetStats();
            public Builder currentSpend(Double v) { stats.currentSpend = v; return this; }
            public Builder totalBudget(Double v) { stats.totalBudget = v; return this; }
            public Builder todaySpend(Double v) { stats.todaySpend = v; return this; }
            public Builder dailyBudget(Double v) { stats.dailyBudget = v; return this; }
            public Builder remainingBudget(Double v) { stats.remainingBudget = v; return this; }
            public Builder remainingDailyBudget(Double v) { stats.remainingDailyBudget = v; return this; }
            public BudgetStats build() { return stats; }
        }
    }
}
