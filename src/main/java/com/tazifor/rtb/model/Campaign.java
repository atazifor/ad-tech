package com.tazifor.rtb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Campaign Model
 *
 * Represents an advertising campaign with targeting rules,
 * budget constraints, and bidding parameters.
 *
 * Stored in Aerospike for fast lookup during bid request processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Campaign {

    // ===== Basic Info =====
    private String id;
    private String name;
    private String advertiserId;

    // ===== Status =====
    private CampaignStatus status;

    // ===== Budget & Pacing =====
    private Double totalBudget;      // Total campaign budget
    private Double dailyBudget;      // Daily spend cap
    private Double currentSpend;     // Current total spend
    private Double todaySpend;       // Today's spend

    // ===== Bidding =====
    private Double bidPrice;         // CPM bid price
    private Double maxBid;           // Maximum allowed bid
    private Double minBid;           // Minimum bid

    // ===== Targeting Rules =====
    private TargetingRules targeting;

    // ===== Creative Info =====
    private String creativeId;
    private String adMarkup;         // HTML/VAST markup
    private List<String> advertiserDomains;

    // ===== Frequency Capping =====
    private Integer frequencyCapImpressions;  // Max impressions per user
    private Integer frequencyCapHours;        // Time window in hours

    // ===== Timestamps =====
    private Instant startDate;
    private Instant endDate;
    private Instant createdAt;
    private Instant updatedAt;

    // ===== Performance Tracking =====
    private Long impressions;
    private Long clicks;
    private Long conversions;

    /**
     * Campaign Status
     */
    public enum CampaignStatus {
        DRAFT,           // Not yet active
        ACTIVE,          // Currently running
        PAUSED,          // Manually paused
        BUDGET_DEPLETED, // Budget exhausted
        COMPLETED,       // End date reached
        ARCHIVED         // Deleted/archived
    }

    /**
     * Targeting Rules
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetingRules {

        // Geographic targeting
        private Set<String> countries;        // ISO country codes
        private Set<String> regions;          // State/province codes
        private Set<String> cities;

        // Device targeting
        private Set<Integer> deviceTypes;     // 1=mobile, 2=PC, 3=TV, etc.
        private Set<String> operatingSystems; // iOS, Android, Windows, etc.
        private Set<String> browsers;

        // Site/App targeting
        private Set<String> domains;          // Allowlist
        private Set<String> blockedDomains;   // Blocklist
        private Set<String> categories;       // IAB categories

        // Time targeting
        private Set<Integer> dayOfWeek;       // 0=Sun, 1=Mon, ..., 6=Sat
        private Integer hourStart;            // Start hour (0-23)
        private Integer hourEnd;              // End hour (0-23)

        // User targeting
        private Set<String> userSegments;     // Audience segments
        private Integer minAge;
        private Integer maxAge;
        private Set<String> genders;          // M, F

        // Inventory targeting
        private Double minBidFloor;           // Minimum bid floor to target
        private Double maxBidFloor;
        private Set<String> adSizes;          // e.g., "300x250", "728x90"
    }

    /**
     * Check if campaign is active and can bid
     */
    public boolean canBid() {
        if (status != CampaignStatus.ACTIVE) {
            return false;
        }

        Instant now = Instant.now();
        if (startDate != null && now.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && now.isAfter(endDate)) {
            return false;
        }

        // Check budget
        if (totalBudget != null && currentSpend != null && currentSpend >= totalBudget) {
            return false;
        }
        if (dailyBudget != null && todaySpend != null && todaySpend >= dailyBudget) {
            return false;
        }

        return true;
    }

    /**
     * Get effective bid price for this campaign
     */
    @JsonIgnore
    public Double getEffectiveBidPrice() {
        if (bidPrice == null) {
            return 1.0; // Default $1 CPM
        }

        // Ensure bid is within min/max bounds
        double bid = bidPrice;
        if (minBid != null && bid < minBid) {
            bid = minBid;
        }
        if (maxBid != null && bid > maxBid) {
            bid = maxBid;
        }

        return bid;
    }
}
