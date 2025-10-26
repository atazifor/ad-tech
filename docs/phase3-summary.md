# Phase 3: Quick Reference Card

## 🚀 Quick Start

```bash
# Start Aerospike
docker-compose up -d

# Build and run (with Phase 3 budget tracking)
mvn clean package
mvn spring-boot:run

# Run Phase 3 tests
chmod +x test-phase3.sh
./test-phase3.sh
```

## 📡 New API Endpoints

### Budget Management
```bash
# Get budget statistics
curl http://localhost:8080/api/campaigns/{id}/budget

# Add more budget
curl -X POST "http://localhost:8080/api/campaigns/{id}/add-budget?amount=100.00"

# Reset daily spend (manual)
curl -X POST http://localhost:8080/api/campaigns/{id}/reset-daily
```

## 💰 Budget Math Quick Reference

```
Campaign Bid: $2.50 CPM
Cost per impression: $2.50 ÷ 1000 = $0.0025

Examples:
- 1 impression = $0.0025
- 100 impressions = $0.25
- 1,000 impressions = $2.50
- 10,000 impressions = $25.00

Budget calculation:
$1000 budget ÷ $2.50 CPM = 400,000 impressions
```

## ⚛️ Atomic Operations Pattern

### The Problem (Race Condition)
```java
// ❌ WRONG - Not atomic, race condition!
Double spend = client.get(key, "currentSpend"); // $99.50
spend += 0.0025;                                 // $99.5025
client.put(key, new Bin("currentSpend", spend)); // RACE!
```

### The Solution (Atomic)
```java
// ✓ CORRECT - Atomic, no race condition
Record result = client.operate(
    policy, 
    key,
    Operation.add(new Bin("currentSpend", 0.0025)),
    Operation.get("currentSpend")
);
// All operations happen atomically on server
```

## 🎯 Key Code Patterns

### Pattern 1: Pre-Check Budget (Fast Filter)
```java
// Fast, non-atomic pre-check
boolean canAfford = budgetService.canAffordBid(campaignId, bidPrice);
if (!canAfford) {
    return; // Skip expensive operations
}

// Later: Atomic reservation
BudgetReservation reservation = budgetService.reserveBudget(...);
```

**Why:** Pre-check is cheap (0.1ms), filters 50-80% of campaigns

### Pattern 2: Atomic Reservation with Rollback
```java
// Atomic operation
Record result = client.operate(policy, key,
    Operation.add(new Bin("currentSpend", cost)),
    Operation.get("currentSpend"),
    Operation.get("totalBudget")
);

// Double-check for overspend
if (result.getDouble("currentSpend") > result.getDouble("totalBudget")) {
    // Rollback atomically
    client.operate(policy, key,
        Operation.add(new Bin("currentSpend", -cost))
    );
    return BudgetReservation.failed();
}
```

**Why:** Race condition between pre-check and atomic op is possible

### Pattern 3: Async Campaign Pause
```java
// Don't block bid response
new Thread(() -> {
    budgetService.pauseCampaign(campaignId, reason);
}).start();

// Return bid immediately
return bidResponse;
```

**Why:** Campaign pause takes 2-5ms, bid response must be fast

## 📊 Performance Targets

| Metric | Phase 2 | Phase 3 | Impact |
|--------|---------|---------|--------|
| Average latency | 2-5ms | 3-7ms | +2ms (acceptable) |
| Throughput | 10K QPS | 9.5K QPS | -5% (worth it!) |
| Overspending | N/A | 0% | ✓ Perfect! |
| Budget accuracy | N/A | 0.0001 | 4 decimal precision |

## 🧮 Budget Calculations

### Total Budget Depletion
```
Budget: $1000
Bid: $5.00 CPM
Cost per impression: $0.005

Max impressions: $1000 ÷ $0.005 = 200,000
After 200,000 impressions: Budget depleted
```

### Daily Budget Limit
```
Total Budget: $10,000
Daily Budget: $500
Campaign runs for: $10,000 ÷ $500 = 20 days

Each day can serve: $500 ÷ $0.005 = 100,000 impressions
After 100K impressions today: Daily budget depleted
Tomorrow: Reset to $500 available
```

## 🚨 Common Issues & Solutions

### Issue: Campaign Overspending
**Symptom:** currentSpend > totalBudget

**Cause:** Not using atomic operations

**Solution:**
```java
// Use Operation.add() not read-modify-write
client.operate(policy, key,
    Operation.add(new Bin("currentSpend", cost))
);
```

### Issue: Lost Precision
**Symptom:** Budget shows $99 instead of $99.5025

**Cause:** Using Integer instead of Double

**Solution:**
```java
// ❌ WRONG
private Integer currentSpend;

// ✓ CORRECT
private Double currentSpend;
```

### Issue: Slow Budget Checks
**Symptom:** Latency >50ms

**Cause:** Checking budget for every campaign

**Solution:**
```java
// Filter campaigns BEFORE budget check
List<Campaign> candidates = campaigns.stream()
    .filter(c -> matchesTargeting(c, request))
    .collect(Collectors.toList());

// Now check budget only for matching campaigns
candidates = candidates.stream()
    .filter(c -> budgetService.canAffordBid(c.getId(), c.getBidPrice()))
    .collect(Collectors.toList());
```

### Issue: Campaign Not Pausing
**Symptom:** Campaign continues bidding after budget depleted

**Cause:** Cache not refreshed or pause check failing

**Solution:**
```bash
# Force cache refresh
mvn spring-boot:run  # Restart application

# Or wait 60 seconds for auto-refresh
```

## 🎓 Key Concepts

### Atomic Operations
```
Definition: Operations that execute completely or not at all
Key Property: No other thread can interfere during execution
Aerospike Implementation: Master node locks record during operation
Performance: ~1-2ms (5-50x faster than DB transactions)
```

### Race Conditions
```
Definition: When timing of threads affects correctness
Example: Two threads read $99.50, both add $0.50, both write $100
Result: Lost one of the $0.50 additions!
Solution: Use atomic operations
```

### Budget Precision
```
Why Double: Need 4 decimal places ($0.0025)
Why NOT BigDecimal: Too slow (5-10x slower than Double)
Why NOT Integer: Loses all cents (can't track $0.0025)
Trade-off: Double is perfect for our use case
```

## 📈 Monitoring Commands

### Check Budget Status
```bash
# Single campaign
curl http://localhost:8080/api/campaigns/CAMPAIGN_ID/budget | jq

# Watch real-time
watch -n 1 'curl -s http://localhost:8080/api/campaigns/CAMPAIGN_ID/budget | jq'
```

### Track Spending
```bash
# Monitor logs for budget events
tail -f logs/application.log | grep BUDGET
```

### Expected Log Output
```
[BUDGET] Campaign campaign-abc reserved $0.0025
[BUDGET] Campaign campaign-abc current spend: $99.5025
[BUDGET] Campaign campaign-abc paused: Budget depleted
```

## 🧪 Testing Scenarios

### Test 1: Basic Spend
```bash
# Send 10 requests
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/bid \
    -H "Content-Type: application/json" \
    -d @bid-request.json
  sleep 0.1
done

# Check spend
# Expected: $0.025 (10 × $0.0025)
```

### Test 2: Race Condition
```bash
# Send 100 CONCURRENT requests
for i in {1..100}; do
  curl -X POST http://localhost:8080/api/bid \
    -H "Content-Type: application/json" \
    -d @bid-request.json &
done
wait

# Check spend
# Expected: ≤ budget (no overspending!)
```

### Test 3: Budget Depletion
```bash
# Create $1 budget campaign
# Send requests until depleted
# Check status

# Expected: status = "BUDGET_DEPLETED"
```

## 💡 Pro Tips

1. **Always use atomic operations for money** - Read-modify-write is never safe
2. **Pre-check budget before targeting** - Save 50-80% of CPU
3. **Use Double, not BigDecimal** - 5-10x faster, enough precision
4. **Monitor overspending closely** - Should be exactly 0%
5. **Test with concurrent requests** - Only way to catch race conditions

## 🔜 Phase 4 Preview

### Frequency Capping
```
Challenge: Track impressions per user, not per campaign
Scale: Millions of users vs hundreds of campaigns
Solution: Same atomic operations, different key structure

User key: "user:USER_ID:campaign:CAMPAIGN_ID:daily"
Counter: Increment atomically on each impression
Check: If counter >= cap, don't show ad
```

### New Patterns
```java
// Atomic user counter
client.operate(policy, userKey,
    Operation.add(new Bin("impressions", 1)),
    Operation.get("impressions")
);

// TTL for daily reset
WritePolicy policy = new WritePolicy();
policy.expiration = 86400; // 24 hours
```

Ready for Phase 4? 🚀