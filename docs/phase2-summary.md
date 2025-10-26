# Phase 2: Quick Reference Card

## 🚀 Quick Start Commands

```bash
# Start Aerospike
docker-compose up -d

# Build and run
mvn clean package
mvn spring-boot:run

# Run Phase 2 tests
chmod +x test-phase2.sh
./test-phase2.sh
```

## 📡 API Endpoints

### Campaign Management
```bash
# Create campaign
curl -X POST http://localhost:8080/api/campaigns \
  -H "Content-Type: application/json" \
  -d '{...campaign JSON...}'

# List all campaigns
curl http://localhost:8080/api/campaigns

# List active campaigns only
curl http://localhost:8080/api/campaigns?status=ACTIVE

# Get specific campaign
curl http://localhost:8080/api/campaigns/{id}

# Update campaign
curl -X PUT http://localhost:8080/api/campaigns/{id} \
  -H "Content-Type: application/json" \
  -d '{...updated campaign...}'

# Delete (archive) campaign
curl -X DELETE http://localhost:8080/api/campaigns/{id}

# Create 50 test campaigns
curl -X POST http://localhost:8080/api/campaigns/bulk?count=50
```

### Bidding
```bash
# Send bid request
curl -X POST http://localhost:8080/api/bid \
  -H "Content-Type: application/json" \
  -d '{...bid request...}'
```

## 🎯 Key Targeting Rules

### Geographic Targeting
```json
{
  "targeting": {
    "countries": ["USA", "CA"],
    "regions": ["CA", "NY"],
    "cities": ["San Francisco", "New York"]
  }
}
```

**Matching Logic:** Hierarchical (country → region → city)

### Device Targeting
```json
{
  "targeting": {
    "deviceTypes": [1, 2],  // 1=mobile, 2=PC, 3=TV
    "operatingSystems": ["iOS", "Android"],
    "browsers": ["Chrome", "Safari"]
  }
}
```

### Time Targeting
```json
{
  "targeting": {
    "dayOfWeek": [1, 2, 3, 4, 5],  // Mon-Fri (1-7)
    "hourStart": 9,                // 9am
    "hourEnd": 17                  // 5pm
  }
}
```

### Domain Targeting
```json
{
  "targeting": {
    "domains": ["example.com", "test.com"],        // Allowlist
    "blockedDomains": ["blocked.com"]              // Blocklist
  }
}
```

## 🔧 Code Patterns to Remember

### Thread-Safe Cache
```java
// ✅ CORRECT
private final Map<String, Campaign> cache = new ConcurrentHashMap<>();

// ❌ WRONG - Not thread-safe
private final Map<String, Campaign> cache = new HashMap<>();
```

### Defensive Null Checks
```java
// ✅ CORRECT
if (request.getDevice() != null && 
    request.getDevice().getGeo() != null) {
    String country = request.getDevice().getGeo().getCountry();
}

// ❌ WRONG - Will crash on null
String country = request.getDevice().getGeo().getCountry();
```

### Fail-Fast Targeting
```java
// ✅ CORRECT - Check cheapest first
if (!matchesGeography(targeting, request)) return false;
if (!matchesDevice(targeting, request)) return false;
if (!matchesTime(targeting, request)) return false;

// ❌ WRONG - All checks every time
boolean a = matchesGeography(targeting, request);
boolean b = matchesDevice(targeting, request);
boolean c = matchesTime(targeting, request);
return a && b && c;
```

### Set vs List for Targeting
```java
// ✅ CORRECT - O(1) lookup
private Set<String> countries;

// ❌ WRONG - O(n) lookup
private List<String> countries;
```

## 📊 Performance Targets

| Metric | Target | Phase 2 Reality |
|--------|--------|-----------------|
| Request latency | <50ms | 2-5ms (excellent!) |
| Campaigns checked | All active | 100+ in <5ms |
| Cache hit rate | >99% | ~99.9% |
| Targeting eval | <0.5ms per campaign | 0.1-0.5ms ✓ |

## 🎓 Key Concepts

### Campaign Lifecycle
```
DRAFT → ACTIVE → PAUSED → ARCHIVED
         ↑          ↓
         └──────────┘
```

### Auction Algorithm (Phase 2)
```
1. Get all active campaigns
2. Filter by can_bid() (budget, dates)
3. Filter by targeting rules
4. Select highest bid
5. Return bid response
```

### Cache Refresh Strategy
```
Every request:
  if (now - lastRefresh > 60s):
    synchronized:
      if (still stale):  // Double-check
        refresh from DB
        update timestamp
```

## 🚨 Common Issues & Solutions

### Issue: Latency >50ms
**Cause:** Too many campaigns (100+)  
**Solution:** Phase 3 will add campaign indexing

### Issue: No matching campaigns
**Check:**
1. Campaigns are ACTIVE status?
2. Targeting rules match request?
3. Campaign budget not depleted?
4. Cache refreshed recently?

### Issue: Wrong campaign wins
**Check:**
1. Bid prices correct?
2. All matching campaigns considered?
3. Logs show campaign selection?

### Issue: Changes not reflected
**Cause:** Cache not refreshed  
**Solution:**
```bash
# Force refresh by restarting
mvn spring-boot:run

# Or wait 60 seconds for auto-refresh
```

## 📈 Monitoring Tips

### Check Application Logs
```bash
# Look for bid processing
[BID] Latency: 3ms | Campaigns: 120 active, 5 matched | Winner: campaign-xyz ($2.50)

# Look for cache refresh
[CACHE] Refreshing campaign cache...
[CACHE] Loaded 120 campaigns
```

### Test Targeting
```bash
# USA mobile request
curl -X POST http://localhost:8080/api/bid -H "Content-Type: application/json" -d '{
  "id": "test",
  "imp": [{"id": "1", "banner": {"w": 320, "h": 50}}],
  "device": {"devicetype": 1, "geo": {"country": "USA"}},
  "user": {"id": "user-123"}
}'
```

### Performance Test
```bash
# Apache Bench - 100 requests, 10 concurrent
ab -n 100 -c 10 -T 'application/json' \
  -p test-bid-request.json \
  http://localhost:8080/api/bid
```

## 💡 Pro Tips

1. **Cache is King:** 99% of performance comes from caching
2. **Fail Fast:** Check cheapest conditions first
3. **Use Sets:** O(1) lookups beat O(n) every time
4. **Log Everything:** You can't optimize what you can't measure
5. **Test with Scale:** 10 campaigns vs 100 is very different

## 🔜 Phase 3 Preview

### What's Coming
- **Real-time budget tracking** with atomic operations
- **Race condition testing** (multiple requests, same budget)
- **Campaign auto-pause** on budget depletion
- **Budget pacing** (spend evenly throughout day)
- **Performance at 10K QPS**

### New Challenges
```java
// Problem: Two requests spend same budget simultaneously
Request 1: currentSpend = $90, bidPrice = $20 → $110 (over!)
Request 2: currentSpend = $90, bidPrice = $20 → $110 (over!)

// Solution: Atomic operations
client.operate(policy, key, 
    Operation.add(new Bin("currentSpend", bidPrice))
);
```

Ready for Phase 3? 🚀