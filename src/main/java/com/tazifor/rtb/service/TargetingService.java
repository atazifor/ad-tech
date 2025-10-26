package com.tazifor.rtb.service;

import com.tazifor.rtb.model.BidRequest;
import com.tazifor.rtb.model.Campaign;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * TargetingService - Phase 2: Campaign Targeting Logic
 *
 * DESIGN PHILOSOPHY:
 * This service evaluates if a bid request matches a campaign's targeting rules.
 * The key principle is FAIL FAST - we check cheapest conditions first and bail early
 * if targeting doesn't match. This minimizes CPU cycles per request.
 *
 * PERFORMANCE CONSIDERATIONS:
 * - Each targeting check adds latency (~0.1-0.5ms)
 * - We order checks from fastest to slowest
 * - We use early returns to skip unnecessary checks
 * - We avoid expensive operations (regex, string parsing) in hot path
 *
 * WHY THIS MATTERS:
 * At 10,000 QPS, saving 1ms per request = 10 seconds of CPU per second!
 * This service is called for EVERY campaign on EVERY bid request.
 */
@Service
public class TargetingService {

    /**
     * Master targeting evaluation method
     *
     * DESIGN DECISION: Return boolean (match/no-match) instead of detailed reasons
     * WHY: In production RTB, we don't need to know WHY targeting failed, only IF it failed.
     * This saves object allocations and makes the code faster.
     *
     * ORDERING STRATEGY:
     * 1. Check null targeting (fastest - single if statement)
     * 2. Check geographic targeting (medium - Set lookups)
     * 3. Check device targeting (medium - Set lookups)
     * 4. Check time targeting (slower - date/time calculations)
     *
     * @param campaign The campaign to evaluate
     * @param request The incoming bid request
     * @return true if request matches campaign targeting, false otherwise
     */
    public boolean matchesTargeting(Campaign campaign, BidRequest request) {
        Campaign.TargetingRules targeting = campaign.getTargeting();

        // OPTIMIZATION: If no targeting rules exist, match everything
        // This is common for broad campaigns and saves all subsequent checks
        if (targeting == null) {
            return true;
        }

        // Check geographic targeting
        // WHY FIRST: Geography is most commonly used and Set.contains() is O(1)
        if (!matchesGeography(targeting, request)) {
            return false; // FAIL FAST: Stop checking if geo doesn't match
        }

        // Check device targeting
        // WHY SECOND: Device checks are also fast Set lookups
        if (!matchesDevice(targeting, request)) {
            return false; // FAIL FAST
        }

        // Check time targeting
        // WHY LAST: Time checks involve date/time objects which are more expensive
        if (!matchesTimeWindow(targeting, request)) {
            return false; // FAIL FAST
        }

        // Check site/domain targeting
        if (!matchesSite(targeting, request)) {
            return false;
        }

        // All checks passed!
        return true;
    }

    /**
     * Geographic Targeting Evaluation
     *
     * DESIGN DECISION: Hierarchical matching (country -> region -> city)
     * WHY: Most campaigns target at country level, fewer at city level.
     * This matches real-world advertising patterns.
     *
     * PERFORMANCE NOTE: Set.contains() is O(1) average case, so this is very fast.
     * Even with 100 countries, lookup is constant time.
     *
     * IMPORTANT: We use Set<String> not List<String> for targeting rules
     * WHY: Set.contains() is O(1), List.contains() is O(n)
     * At 10K QPS with 50 countries, this matters!
     */
    private boolean matchesGeography(Campaign.TargetingRules targeting, BidRequest request) {
        // Extract geo from request
        // DEFENSIVE PROGRAMMING: Handle missing geo data gracefully
        if (request.getDevice() == null || request.getDevice().getGeo() == null) {
            // DECISION: If no geo data, only match campaigns with no geo targeting
            // WHY: We can't verify geo targeting without geo data
            return targeting.getCountries() == null &&
                targeting.getRegions() == null &&
                targeting.getCities() == null;
        }

        BidRequest.Geo geo = request.getDevice().getGeo();

        // Check country targeting
        // MOST COMMON: 80%+ of campaigns use country targeting
        if (targeting.getCountries() != null && !targeting.getCountries().isEmpty()) {
            String country = geo.getCountry();
            if (country == null || !targeting.getCountries().contains(country)) {
                return false; // Country doesn't match
            }
        }

        // Check region/state targeting
        // LESS COMMON: Maybe 20% of campaigns target specific regions
        if (targeting.getRegions() != null && !targeting.getRegions().isEmpty()) {
            String region = geo.getRegion();
            if (region == null || !targeting.getRegions().contains(region)) {
                return false; // Region doesn't match
            }
        }

        // Check city targeting
        // RARE: Only 5% of campaigns target specific cities
        // COST: City strings are longer, so comparison is slightly slower
        if (targeting.getCities() != null && !targeting.getCities().isEmpty()) {
            String city = geo.getCity();
            if (city == null || !targeting.getCities().contains(city)) {
                return false; // City doesn't match
            }
        }

        return true; // All geographic checks passed
    }

    /**
     * Device Targeting Evaluation
     *
     * DESIGN PATTERN: Multiple device dimensions
     * - Device type (mobile, desktop, tablet, TV, etc.)
     * - Operating system (iOS, Android, Windows)
     * - Browser (Chrome, Safari, Firefox)
     *
     * WHY SEPARATE DIMENSIONS: Campaigns often target "iOS users" (any device)
     * or "Chrome users" (any OS). This gives maximum flexibility.
     *
     * PERFORMANCE: All checks are Set.contains() = O(1)
     */
    private boolean matchesDevice(Campaign.TargetingRules targeting, BidRequest request) {
        if (request.getDevice() == null) {
            // DECISION: No device info = only match campaigns with no device targeting
            return targeting.getDeviceTypes() == null &&
                targeting.getOperatingSystems() == null &&
                targeting.getBrowsers() == null;
        }

        BidRequest.Device device = request.getDevice();

        // Check device type (1=mobile, 2=PC, 3=TV, etc.)
        // COMMON: ~50% of campaigns specify device type
        // EXAMPLE: "Mobile-only campaign" or "Desktop-only campaign"
        if (targeting.getDeviceTypes() != null && !targeting.getDeviceTypes().isEmpty()) {
            Integer deviceType = device.getDeviceType();
            if (deviceType == null || !targeting.getDeviceTypes().contains(deviceType)) {
                return false;
            }
        }

        // Check operating system
        // COMMON: ~30% of campaigns target specific OS
        // EXAMPLE: "iOS users only" for app install campaigns
        if (targeting.getOperatingSystems() != null && !targeting.getOperatingSystems().isEmpty()) {
            String os = device.getOs();
            if (os == null || !targeting.getOperatingSystems().contains(os)) {
                return false;
            }
        }

        // Check browser
        // RARE: <10% of campaigns care about browser
        // EXAMPLE: "Chrome users only" for web push notifications
        if (targeting.getBrowsers() != null && !targeting.getBrowsers().isEmpty()) {
            // COMPLEXITY: Browser is extracted from User-Agent string
            // In Phase 2, we simplify this. Phase 6 can add full UA parsing.
            String browser = extractBrowser(device.getUa());
            if (browser == null || !targeting.getBrowsers().contains(browser)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Time-Based Targeting Evaluation
     *
     * DESIGN PATTERN: Two dimensions of time targeting
     * 1. Day of week (Monday-Sunday)
     * 2. Hour of day (0-23)
     *
     * USE CASES:
     * - "Lunch delivery ads" -> Monday-Friday, 11am-2pm
     * - "Weekend sales" -> Saturday-Sunday, all hours
     * - "Night owl campaigns" -> All days, 10pm-6am
     *
     * PERFORMANCE WARNING: Time operations are more expensive than Set lookups
     * WHY: Requires current time, timezone handling, date math
     * MITIGATION: Check time last (after fast geo/device checks)
     *
     * TIMEZONE CONSIDERATION: We use UTC for consistency
     * PRODUCTION TIP: You might want to use advertiser's timezone instead
     */
    private boolean matchesTimeWindow(Campaign.TargetingRules targeting, BidRequest request) {
        // If no time targeting, match all times
        if ((targeting.getDayOfWeek() == null || targeting.getDayOfWeek().isEmpty()) &&
            targeting.getHourStart() == null && targeting.getHourEnd() == null) {
            return true;
        }

        // Get current time in UTC
        // PRODUCTION NOTE: Consider using request timestamp if available
        // WHY: Bid request might have traveled through network, timestamp is more accurate
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));

        // Check day of week targeting
        // EXAMPLE: Campaign only runs Monday-Friday (day 1-5)
        if (targeting.getDayOfWeek() != null && !targeting.getDayOfWeek().isEmpty()) {
            int dayOfWeek = now.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
            if (!targeting.getDayOfWeek().contains(dayOfWeek)) {
                return false;
            }
        }

        // Check hour of day targeting
        // EXAMPLE: Campaign runs 9am-5pm (hour 9-17)
        // COMPLEXITY: Handles wrap-around (e.g., 10pm-6am crosses midnight)
        if (targeting.getHourStart() != null && targeting.getHourEnd() != null) {
            int currentHour = now.getHour(); // 0-23

            // CASE 1: Same day window (e.g., 9am-5pm)
            if (targeting.getHourStart() <= targeting.getHourEnd()) {
                if (currentHour < targeting.getHourStart() || currentHour > targeting.getHourEnd()) {
                    return false;
                }
            }
            // CASE 2: Crosses midnight (e.g., 10pm-6am)
            else {
                if (currentHour > targeting.getHourEnd() && currentHour < targeting.getHourStart()) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Site/Domain Targeting Evaluation
     *
     * DESIGN PATTERN: Both allowlist and blocklist
     * - Allowlist (domains): Campaign ONLY runs on these domains
     * - Blocklist (blockedDomains): Campaign NEVER runs on these domains
     *
     * PRIORITY: Blocklist wins over allowlist
     * WHY: Brand safety is more important than reach
     *
     * COMMON SCENARIO: Advertiser says "Run everywhere EXCEPT adult sites"
     */
    private boolean matchesSite(Campaign.TargetingRules targeting, BidRequest request) {
        // Extract domain from site or app
        String domain = null;
        if (request.getSite() != null) {
            domain = request.getSite().getDomain();
        } else if (request.getApp() != null) {
            domain = request.getApp().getBundle();
        }

        if (domain == null) {
            // No domain info - only match if no domain targeting exists
            return targeting.getDomains() == null && targeting.getBlockedDomains() == null;
        }

        // Check blocklist FIRST (safety > reach)
        // CRITICAL: If domain is blocked, NEVER show ad
        if (targeting.getBlockedDomains() != null &&
            targeting.getBlockedDomains().contains(domain)) {
            return false; // BLOCKED!
        }

        // Check allowlist
        // If allowlist exists, domain MUST be in it
        if (targeting.getDomains() != null && !targeting.getDomains().isEmpty()) {
            if (!targeting.getDomains().contains(domain)) {
                return false; // Not in allowlist
            }
        }

        return true;
    }

    /**
     * Extract browser from User-Agent string
     *
     * SIMPLIFIED: This is a basic implementation for Phase 2
     * PRODUCTION: Use a proper UA parser library (e.g., UAParser)
     *
     * WHY SIMPLIFIED: UA parsing is expensive (~1-2ms)
     * At 10K QPS, that's 10-20 seconds of CPU per second!
     * Phase 2 uses simple string matching for common browsers.
     *
     * TRADE-OFF: Less accurate but much faster
     */
    private String extractBrowser(String userAgent) {
        if (userAgent == null) {
            return null;
        }

        // Simple substring matching
        // PERFORMANCE: String.contains() is fast enough for common cases
        String ua = userAgent.toLowerCase();

        // Check most common browsers first (ordered by market share)
        if (ua.contains("chrome") && !ua.contains("edg")) return "Chrome";
        if (ua.contains("safari") && !ua.contains("chrome")) return "Safari";
        if (ua.contains("firefox")) return "Firefox";
        if (ua.contains("edg")) return "Edge";
        if (ua.contains("msie") || ua.contains("trident")) return "IE";

        return "Other"; // Unknown browser
    }

    /**
     * Calculate targeting match score (optional, for Phase 2+)
     *
     * FUTURE ENHANCEMENT: Not just "match or no match" but "how well does it match?"
     * USE CASE: Multiple campaigns match - pick the most relevant one
     *
     * SCORING EXAMPLE:
     * - Exact city match: +10 points
     * - Region match: +5 points
     * - Country match: +2 points
     * - Device match: +3 points
     * - Time match: +1 point
     *
     * This allows smart campaign selection: "Best matching campaign wins the auction"
     *
     * NOTE: Not implemented in Phase 2 to keep it simple.
     * Phase 4 will add this for advanced campaign selection.
     */
}
