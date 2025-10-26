# RTB Engine - Phase 2: Campaign Targeting & Management

## 🎯 Phase 2 Overview

**What We're Building:**
- Campaign targeting system (geo, device, time)
- Multiple active campaigns
- Campaign selection algorithm
- Campaign management API (CRUD)

**Why This Matters:**
Real RTB engines must evaluate 100+ campaigns per request in <50ms. This phase shows you how to do it efficiently.

---

## 📚 Key Files & What They Do

### 1. TargetingService.java
**Purpose:** Evaluates if a bid request matches campaign targeting rules

**Key Design Decisions:**

#### Decision 1: Fail-Fast Ordering
```java
if (!matchesGeography(targeting, request)) {
    return false; // Stop immediately if geo doesn't match
}
```
**Why:** At 10K QPS, saving 0.5ms per request = 5 seconds of CPU per second. We check cheapest conditions first.

**Ordering Logic:**
1. **Geography** (fastest) → Set.contains() is O(1)
2. **Device** (fast) → Also Set lookups
3. **Time** (slower) → Requires date/time calculations

**What to Pay Attention To:**
- We use `Set<String>` not `List<String>` for targeting
- **Why:** `Set.contains()` is O(1), `List.contains()` is O(n)
- With 50 countries, this is 50x faster!

#### Decision 2: Defensive Programming
```java
if (request.getDevice() == null || request.getDevice().getGeo() == null) {
    return targeting.getCountries() == null;
}
```
**Why:** Real-world bid requests have missing data. We handle it gracefully instead of crashing.

**What to Pay Attention To:**
- Never assume data exists
- Always check for null before accessing nested objects
- Fail gracefully (return false, not exception)

#### Decision 3: Hierarchical Geography
```java
// Check country first (most common)
if (targeting.getCountries() != null) {
    if (!targeting.getCountries().contains(country)) return false;
}

// Then region (less common)
if (targeting.getRegions() != null) {
    if (!targeting.getRegions().contains(region)) return false;
}
```

**Why:**
- 80% of campaigns target by country
- 20% target by region
- 5% target by city

**What to Pay Attention To:**
- Order checks from most to least common
- Early return saves checking less common conditions
- This mirrors real-world advertising patterns

---

### 2. BiddingService.java (Enhanced)
**Purpose:** Core bidding logic with campaign caching and selection

**Key Design Decisions:**

#### Decision 1: In-Memory Campaign Cache
```java
private final Map<String, Campaign> campaignCache = new ConcurrentHashMap<>();
```

**Why Cache?**
| Operation | Without Cache | With Cache | Savings |
|-----------|--------------|------------|---------|
| Campaign Lookup | 1-2ms (Aerospike) | 0.01ms (memory) | 100-200x faster |
| At 10K QPS | 10-20s CPU/sec | 0.1s CPU/sec | 99% reduction |

**Why ConcurrentHashMap?**
- Multiple threads access cache simultaneously
- Regular HashMap → Race conditions, crashes
- Synchronized HashMap → Slow (locks on every read)
- ConcurrentHashMap → Lock-free reads, perfect for our use case

**What to Pay Attention To:**
```java
private volatile long lastCacheRefresh = 0;
```
- `volatile` ensures visibility across threads
- Without it, one thread might see stale value
- This is subtle but critical for thread safety

#### Decision 2: Cache Refresh Strategy
```java
private static final long CACHE_TTL_MS = 60_000; // 60 seconds
```

**Time-Based Refresh:**
- **Pros:** Simple, predictable
- **Cons:** Can show stale data for up to 60 seconds

**Why 60 Seconds?**
- Campaigns change rarely (minutes to hours)
- 60s is recent enough for most use cases
- Infrequent enough to not impact performance

**Alternative Approaches (Not Used in Phase 2):**
1. **Event-Based:** Invalidate cache when campaign changes
    - Pros: Always fresh data
    - Cons: More complex, requires message queue

2. **LRU Cache:** Only keep recently-used campaigns
    - Pros: Lower memory usage
    - Cons: Cache misses for less popular campaigns

**What to Pay Attention To:**
```java
synchronized (this) {
    if (now - lastCacheRefresh < CACHE_TTL_MS) {
        return; // Double-check pattern
    }
    loadCampaignsFromDatabase();
}
```
This is **double-checked locking:**
1. Check without lock (fast path)
2. If stale, acquire lock
3. Check again (another thread might have refreshed)
4. Refresh if still needed

**Why This Pattern?**
- 99.99% of requests take fast path (no lock)
- Only one thread refreshes at a time
- Prevents duplicate refreshes

#### Decision 3: Campaign Selection Algorithm
```java
private Campaign selectBestCampaign(List<Campaign> campaigns, BidRequest request) {
    return campaigns.stream()
            .max(Comparator.comparing(Campaign::getEffectiveBidPrice))
            .orElse(campaigns.get(0));
}
```

**Phase 2: Simple first-price auction**
- Highest bid wins
- Easy to understand and predict
- Matches advertiser expectations

**Future Enhancements (Phase 4):**
```java
// Consider budget pacing
if (campaign.isSpendingTooFast()) {
    reduceBid();
}

// Consider click-through rate
if (campaign.hasHighCTR()) {
    prioritizeOverHigherBid();
}

// Second-price auction
winningBid = secondHighestBid + 0.01;
```

**What to Pay Attention To:**
- Algorithm is pluggable (easy to change later)
- Phase 2 keeps it simple
- Real-world systems are much more complex

---

### 3. CampaignController.java
**Purpose:** Management API for campaigns (CRUD operations)

**Key Design Decisions:**

#### Decision 1: Server-Side ID Generation
```java
if (campaign.getId() == null) {
    campaign.setId(UUID.randomUUID().toString());
}
```

**Why Server Generates IDs?**
- **Prevents collisions** (guaranteed unique)
- **Simplifies client** (less code)
- **Security** (client can't guess IDs)

**Alternative:** Client provides ID
- **Pros:** Client controls ID format
- **Cons:** Risk of collisions, security issues

#### Decision 2: Soft Delete
```java
// Instead of: client.delete(null, key);
campaign.setStatus(Campaign.CampaignStatus.ARCHIVED);
saveCampaign(campaign);
```

**Why Soft Delete?**
| Hard Delete | Soft Delete |
|-------------|-------------|
| Data lost forever | Can recover |
| Simple | Need to filter archived |
| Clean database | More storage |

**What to Pay Attention To:**
```java
.filter(campaign -> campaign.getStatus() == Campaign.CampaignStatus.ACTIVE)
```
- Always filter out ARCHIVED campaigns
- Forgot this? You'll serve deleted campaigns!
- Production systems add `deleted_at` timestamp

#### Decision 3: Timestamp Tracking
```java
campaign.setCreatedAt(Instant.now());
campaign.setUpdatedAt(Instant.now());
```

**Why Track Timestamps?**
1. **Debugging:** When did this campaign change?
2. **Auditing:** Who changed what when?
3. **Cache invalidation:** Is my cache stale?
4. **Analytics:** Campaign performance over time

**What to Pay Attention To:**
- Always update `updatedAt` on every change
- Use `Instant` not `Date` (better API)
- Store in UTC (avoid timezone issues)

---

## 🔍 Performance Deep Dive

### Latency Breakdown (Typical Request)

```
Total: ~2-5ms
├── Request validation: 0.01ms
├── Cache check: 0.01ms
├── Get active campaigns: 0.1ms (from cache)
├── Targeting evaluation: 0.5-3ms (per matching campaign)
│   ├── Geography check: 0.1ms
│   ├── Device check: 0.1ms
│   └── Time check: 0.2ms
├── Campaign selection: 0.01ms
└── Response building: 0.1ms
```

### Optimization Strategies

#### Strategy 1: Campaign Indexing (Phase 3)
**Problem:** With 1000 campaigns, checking each takes 500ms-3s (too slow!)

**Solution:** Pre-filter by geography
```java
// Instead of checking all 1000 campaigns
campaigns = campaignCache.values(); // 1000 campaigns

// Use geographic index
campaigns = geographicIndex.get("USA"); // ~50 campaigns
```

**Impact:**
- 1000 campaigns → 50 candidates
- 500ms → 25ms (20x faster!)

#### Strategy 2: Parallel Evaluation
```java
List<Campaign> matching = campaigns.parallelStream()
    .filter(c -> targetingService.matchesTargeting(c, request))
    .collect(Collectors.toList());
```

**Impact:**
- 4 CPU cores → 4x faster targeting evaluation
- But adds complexity and overhead
- Only worth it with 100+ campaigns

#### Strategy 3: Bloom Filters (Advanced)
```java
// Quick negative check before expensive evaluation
if (!bloomFilter.mightMatch(request)) {
    return false; // Definitely doesn't match
}
```

**Impact:**
- 99% of campaigns filtered in 0.001ms
- Only evaluate 1% that might match
- Complex to implement correctly

---

## 🎓 Learning Checkpoints

### Checkpoint 1: Threading
**Question:** Why is `ConcurrentHashMap` better than `synchronized HashMap`?

**Answer:**
- `ConcurrentHashMap`: Lock-free reads, locks only specific segments for writes
- `synchronized HashMap`: Every operation locks entire map
- At 10K QPS with 99% reads, ConcurrentHashMap is 100x faster

### Checkpoint 2: Caching
**Question:** Why refresh cache every 60s instead of on every campaign change?

**Answer:**
- Campaign changes are rare (minutes/hours)
- Event-based invalidation requires infrastructure (message queue)
- 60s staleness is acceptable for advertising
- Simple systems are easier to debug and maintain

### Checkpoint 3: Algorithm Design
**Question:** Why check geography before time targeting?

**Answer:**
- Geography: O(1) Set lookup (~0.1ms)
- Time: Date/time calculations (~0.2ms)
- Most campaigns use geography (80%)
- Fewer use time targeting (20%)
- Checking geo first eliminates 80% of campaigns quickly

---

## 🚨 Common Pitfalls

### Pitfall 1: Not Handling Null
```java
// ❌ WRONG - Will crash
String country = request.getDevice().getGeo().getCountry();

// ✅ CORRECT - Defensive
if (request.getDevice() != null && 
    request.getDevice().getGeo() != null) {
    String country = request.getDevice().getGeo().getCountry();
}
```

### Pitfall 2: Using List for Targeting
```java
// ❌ WRONG - O(n) lookup
private List<String> countries;

// ✅ CORRECT - O(1) lookup
private Set<String> countries;
```

### Pitfall 3: Forgetting to Refresh Cache
```java
// After creating campaigns:
biddingService.initializeCache(); // ← Don't forget this!
```

### Pitfall 4: Race Conditions
```java
// ❌ WRONG - Not thread-safe
private Map<String, Campaign> cache = new HashMap<>();

// ✅ CORRECT - Thread-safe
private Map<String, Campaign> cache = new ConcurrentHashMap<>();
```

---

## 📊 Testing Phase 2

### Test 1: Geographic Targeting
```bash
# Create campaigns targeting USA
# Send request from UK
# Expect: No matching campaign (no-bid)
```

### Test 2: Multiple Matches
```bash
# Create campaigns with bids: $1.00, $2.50, $5.00
# Send request that matches all three
# Expect: $5.00 campaign wins
```

### Test 3: Performance
```bash
# Create 100 campaigns
# Send 50 requests
# Expect: Average latency <50ms
```

### Test 4: Cache Refresh
```bash
# Create campaign
# Wait 60 seconds
# Campaign should appear in bidding
```

---

## 🔜 What's Next in Phase 3?

### Budget Tracking Challenges
1. **Race Conditions:** Multiple requests spending same budget simultaneously
2. **Consistency:** Ensuring budget never goes negative
3. **Performance:** Atomic operations can be slow
4. **Pacing:** Spending budget evenly throughout the day

### Solutions We'll Implement
```java
// Atomic budget decrement
client.operate(policy, key, 
    Operation.add(new Bin("currentSpend", bidPrice))
);

// Budget overflow protection
if (currentSpend + bidPrice > totalBudget) {
    pauseCampaign();
}
```

Ready for Phase 3? 🚀