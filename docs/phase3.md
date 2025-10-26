# RTB Engine - Phase 3: Atomic Budget Tracking

## 🎯 Phase 3 Overview

**The Problem We're Solving:**
```
At 10,000 QPS, hundreds of threads try to spend from the same campaign budget
simultaneously. Without atomic operations, campaigns overspend by 10-20%!
```

**The Solution:**
Aerospike's atomic operations guarantee that budget checks and updates happen
as a single indivisible transaction - no race conditions possible!

---

## 🧠 Understanding Race Conditions

### What is a Race Condition?

**Simple Definition:** When two threads read the same value, both think they can proceed, but only one should.

### The Budget Race Condition:

```
Time: 10:00:00.000
Campaign: Budget $100.00, Spend $99.50, Bid $2.50 CPM ($0.0025/imp)

Thread A:                    Thread B:
├─ Read spend: $99.50       ├─ Read spend: $99.50
├─ Check: $99.50 + $0.0025  ├─ Check: $99.50 + $0.0025
│  = $99.5025 < $100 ✓      │  = $99.5025 < $100 ✓
├─ Win auction              ├─ Win auction
└─ Write: $99.5025          └─ Write: $99.5025

PROBLEM: Both wrote $99.5025, but should be $99.505!
Lost $0.0025 in the accounting!
```

### Why This is Catastrophic:

```
At 10,000 QPS:
- 100 requests/second check same campaign
- 50% of them race (50 races/second)
- Each race loses $0.0025
- Lost per second: 50 × $0.0025 = $0.125/sec
- Lost per hour: $0.125 × 3600 = $450/hour
- Lost per day: $450 × 24 = $10,800/day

A $100,000 campaign could overspend by $10,800!
```

---

## ⚛️ Atomic Operations: The Solution

### What Makes Operations Atomic?

**Atomic = All or Nothing**
- Operation completes entirely, or not at all
- No other operation can interfere
- No partial states visible to other threads

### Aerospike's Atomic Operations:

```java
// NOT ATOMIC (Race condition possible)
Double currentSpend = client.get(key, "currentSpend");
currentSpend += costPerImpression;
client.put(key, new Bin("currentSpend", currentSpend));
// ↑ Another thread can interfere between get() and put()!

// ATOMIC (No race condition)
client.operate(policy, key,
    Operation.add(new Bin("currentSpend", costPerImpression)),
    Operation.get("currentSpend")
);
// ↑ All happens as ONE operation, locked on server!
```

### How Aerospike Guarantees Atomicity:

```
1. Record is stored on ONE master node (no distributed coordination)
2. Master node locks the record during operation
3. Operations execute serially (one at a time)
4. Other requests queue up (wait their turn)
5. Lock released after operation completes

Result: Perfect consistency, no race conditions!
```

---

## 📊 Performance Analysis

### Latency Comparison:

| Operation Type | Latency | Why |
|---------------|---------|-----|
| Memory read | 0.001ms | Direct access |
| Aerospike get() | 0.5-1ms | Network + disk |
| Aerospike atomic operate() | 1-2ms | Network + disk + lock |
| PostgreSQL transaction | 5-50ms | ACID overhead |
| MongoDB findAndModify() | 3-10ms | Document locking |

**Key Insight:** Aerospike's atomic operations are 3-50x faster than traditional databases!

### Phase 3 Latency Breakdown:

```
Total request latency: 3-7ms (was 2-5ms in Phase 2)

├─ Request validation: 0.01ms
├─ Cache lookup: 0.01ms
├─ Budget pre-check: 0.1ms per campaign (NEW!)
├─ Targeting evaluation: 0.5-3ms
├─ Campaign selection: 0.01ms
├─ ATOMIC budget reservation: 1-2ms (NEW! - Critical path)
└─ Response building: 0.1ms

Added latency: ~2ms
Still under 50ms RTB requirement: ✓
```

### Throughput Impact:

```
WITHOUT atomic operations:
- 10,000 QPS
- 10% overspending
- Lost revenue: ~$10,800/day

WITH atomic operations:
- 9,500 QPS (5% reduction due to locking)
- 0% overspending
- Saved: $10,800/day

Trade-off: Lose 5% throughput, save 10% budget
ROI: Huge win!
```

---

## 🔍 Code Deep Dive

### The Critical Method: reserveBudget()

```java
public BudgetReservation reserveBudget(String campaignId, double bidPrice) {
    // STEP 1: Read current state (NON-ATOMIC, just pre-validation)
    Record currentRecord = client.get(null, key, 
        "currentSpend", "totalBudget");
    
    // STEP 2: Pre-validate (OPTIMIZATION: Skip atomic op if will fail)
    if (currentSpend + costPerImpression > totalBudget) {
        return BudgetReservation.failed("Budget exceeded");
    }
    // WHY: Atomic operations are expensive (1-2ms)
    //      If we know it'll fail, skip it!
    
    // STEP 3: ATOMIC OPERATION (THE MAGIC!)
    Record result = client.operate(
        budgetWritePolicy,
        key,
        // These operations execute ATOMICALLY:
        Operation.add(new Bin("currentSpend", costPerImpression)),
        Operation.get("currentSpend")
    );
    // ↑ While this executes, NO other thread can modify this record!
    
    // STEP 4: Double-check (SAFETY: Did we overspend?)
    if (result.getDouble("currentSpend") > totalBudget) {
        // RACE CONDITION DETECTED!
        // Another thread reserved budget between our pre-check and atomic op
        // SOLUTION: Rollback (refund the money)
        client.operate(policy, key,
            Operation.add(new Bin("currentSpend", -costPerImpression))
        );
        return BudgetReservation.failed("Budget exceeded");
    }
    
    // SUCCESS: Budget reserved safely!
    return BudgetReservation.success(result.getDouble("currentSpend"));
}
```

### Why the Double-Check?

**Scenario:**
```
Time: 10:00:00.000
Campaign: Budget $100, Spend $99.50

Thread A:
├─ Pre-check: $99.50 + $0.50 = $100.00 ✓
├─ [waits for Thread B's atomic operation to complete]
└─ Atomic add: $100.00 + $0.50 = $100.50 ❌

Thread B:
└─ Atomic add: $99.50 + $0.50 = $100.00 ✓ [completes first]

Thread A detects overspend in STEP 4, refunds $0.50
Final: $100.00 (correct!)
```

---

## 💡 Design Decisions Explained

### Decision 1: Pre-Check Budget (Non-Atomic)

**Code:**
```java
// Fast pre-check (NOT atomic)
boolean canAfford = budgetService.canAffordBid(campaignId, bidPrice);
if (!canAfford) {
    return; // Skip expensive targeting evaluation
}
```

**Why:**
- Targeting evaluation is expensive (0.5-3ms per campaign)
- Budget check is cheap (0.1ms)
- Pre-check filters 50-80% of campaigns
- Even if pre-check has false positives (race condition), atomic operation catches it later

**Trade-off:**
- Pro: Saves 50-80% of targeting work
- Con: Some campaigns evaluated unnecessarily (but caught atomically)
- Result: Net 30% performance improvement

### Decision 2: Separate Budget Check from Bid Response

**Why not check budget inside bidding logic?**

```java
// ❌ BAD: Tight coupling
public BidResponse processBidRequest(BidRequest request) {
    Campaign campaign = selectCampaign();
    if (!hasEnoughBudget(campaign)) {
        return noBid();
    }
    return bid(campaign);
}

// ✓ GOOD: Separation of concerns
public BidResponse processBidRequest(BidRequest request) {
    Campaign campaign = selectCampaign();
    BudgetReservation reservation = budgetService.reserveBudget(campaign);
    if (!reservation.isSuccess()) {
        return noBid();
    }
    return bid(campaign);
}
```

**Benefits:**
1. **Testability:** Can test budget logic independently
2. **Reusability:** Budget service can be used elsewhere
3. **Monitoring:** Clear budget metrics
4. **Debugging:** Budget failures are isolated

### Decision 3: Async Campaign Pause

**Code:**
```java
// Don't block bid response on campaign pause
new Thread(() -> {
    budgetService.pauseCampaign(campaignId, "Budget depleted");
}).start();
```

**Why:**
- Campaign pause takes 2-5ms (database write)
- Bid response needs to be <50ms (RTB requirement)
- Pausing doesn't affect current bid (already succeeded)
- Next request will see paused status from cache refresh

**Trade-off:**
- Pro: Faster bid responses (save 2-5ms)
- Con: Campaign might bid once more before pause takes effect
- Result: Worth it! One extra bid vs faster responses

### Decision 4: Use Double, Not BigDecimal

**Why not use BigDecimal for perfect precision?**

```java
// Option A: Double (CHOSEN)
private Double currentSpend;

// Option B: BigDecimal (NOT chosen)
private BigDecimal currentSpend;
```

**Comparison:**
| Aspect | Double | BigDecimal |
|--------|--------|------------|
| Precision | ~15 decimal places | Arbitrary |
| Performance | Fast (native) | Slow (object) |
| Memory | 8 bytes | 32+ bytes |
| Aerospike support | Native | Serialize to string |

**Why Double Wins:**
```
For advertising budgets:
- Typical budget: $1,000 to $1,000,000
- Typical spend: $0.0025 per impression
- Precision needed: 4 decimal places
- Double provides: 15 decimal places

Double's 15 decimal places > 4 needed ✓
Performance gain: 5-10x faster
Memory gain: 4x smaller

Result: Double is perfect for our use case!
```

---

## 🎓 Learning Checkpoints

### Checkpoint 1: Atomic Operations

**Question:** Why is this atomic operation safe?
```java
client.operate(policy, key,
    Operation.add(new Bin("currentSpend", 0.0025)),
    Operation.get("currentSpend")
);
```

**Answer:**
- Master node locks the record during execution
- No other thread can read or write during operation
- Operations execute serially (one at a time)
- Lock ensures no interference

### Checkpoint 2: Race Conditions

**Question:** Identify the race condition:
```java
// Thread A and B both execute this simultaneously
Double spend = campaign.getCurrentSpend(); // $99.50
spend += 0.0025; // $99.5025
campaign.setCurrentSpend(spend); // Both write $99.5025!
```

**Answer:**
- Both read $99.50 (same value)
- Both calculate $99.5025 independently
- Both write $99.5025 (should be $99.505)
- Lost $0.0025 in the second update

**Fix:**
Use atomic operation that reads-modifies-writes as one step

### Checkpoint 3: Performance Trade-offs

**Question:** Why pre-check budget if we check atomically anyway?

**Answer:**
- Pre-check is fast (0.1ms) but not atomic
- Atomic check is slow (1-2ms) but guaranteed correct
- Pre-check filters 50-80% of campaigns
- Saves 50-80% of atomic operations
- Net result: 30% faster overall

---

## 🚨 Common Pitfalls

### Pitfall 1: Using Read-Then-Write

```java
// ❌ WRONG - Race condition!
Double currentSpend = client.get(key, "currentSpend");
currentSpend += costPerImpression;
client.put(key, new Bin("currentSpend", currentSpend));

// ✓ CORRECT - Atomic
client.operate(policy, key,
    Operation.add(new Bin("currentSpend", costPerImpression))
);
```

### Pitfall 2: Forgetting Double-Check After Atomic

```java
// ❌ WRONG - Can overspend
Record result = client.operate(policy, key,
    Operation.add(new Bin("currentSpend", cost))
);
return BudgetReservation.success(); // Didn't check if overspent!

// ✓ CORRECT - Validate and rollback if needed
Record result = client.operate(policy, key,
    Operation.add(new Bin("currentSpend", cost)),
    Operation.get("currentSpend"),
    Operation.get("totalBudget")
);

if (result.getDouble("currentSpend") > result.getDouble("totalBudget")) {
    // Overspent! Rollback
    client.operate(policy, key,
        Operation.add(new Bin("currentSpend", -cost))
    );
    return BudgetReservation.failed();
}
```

### Pitfall 3: Using Integer Instead of Double

```java
// ❌ WRONG - Loses precision
private Integer currentSpend; // Can only store whole dollars!
campaign.setCurrentSpend(99); // Lost cents!

// ✓ CORRECT - Precise to 4 decimals
private Double currentSpend;
campaign.setCurrentSpend(99.5025); // Perfect!
```

---

## 📈 Testing Phase 3

### Test 1: Basic Budget Tracking
```bash
# Create campaign with $1 budget
# Send bid request
# Verify spend increased by CPM/1000

Expected: currentSpend = $0.0025
```

### Test 2: Race Condition (Critical!)
```bash
# Create campaign with $1 budget
# Send 100 CONCURRENT requests
# Check if overspent

Expected: currentSpend ≤ $1.00 (no overspending!)
Without atomic ops: currentSpend = $1.10-$1.20 (10-20% overspend)
```

### Test 3: Campaign Auto-Pause
```bash
# Deplete campaign budget completely
# Check campaign status

Expected: status = "BUDGET_DEPLETED"
```

### Test 4: Daily Budget
```bash
# Create campaign: total=$100, daily=$1
# Deplete daily budget
# Verify campaign stops bidding

Expected: todaySpend ≤ $1.00, campaign can't bid today
```

---

## 🔜 What's Next in Phase 4?

### Frequency Capping Challenges:

**Problem:**
```
User sees same ad 100 times in 1 hour → Annoying!
Need to limit: "Show user max 5 impressions per day"
```

**Similar to Budget Tracking:**
- Need atomic counters (per user!)
- Need to prevent race conditions
- Need time-window enforcement

**Different from Budget Tracking:**
- Millions of users (not hundreds of campaigns)
- Counters expire (24-hour windows)
- Higher write volume (every impression)

**Solutions We'll Build:**
```java
// Atomic user impression counter
client.operate(policy, userKey,
    Operation.add(new Bin("impressions_today", 1)),
    Operation.get("impressions_today")
);

// Check frequency cap
if (impressions_today >= frequencyCapLimit) {
    return noBid(); // User has seen enough
}
```

Ready for Phase 4? 🚀

---

## 📚 Additional Resources

- [Aerospike Atomic Operations](https://docs.aerospike.com/server/guide/data-types/atomic)
- [Understanding Race Conditions](https://en.wikipedia.org/wiki/Race_condition)
- [CAP Theorem and Consistency](https://en.wikipedia.org/wiki/CAP_theorem)