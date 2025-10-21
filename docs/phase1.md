# RTB Engine - Phase 1: Foundation & OpenRTB Protocol

## 🎯 Phase 1 Goals

Build the foundation of our RTB engine:
- ✅ Spring Boot application structure
- ✅ OpenRTB 2.5 protocol models
- ✅ Aerospike connection
- ✅ Basic bidding logic
- ✅ First bid request/response

## 📁 Project Structure

```
rtb-engine/
├── src/main/java/com/example/rtb/
│   ├── RtbEngineApplication.java
│   ├── config/
│   │   └── AerospikeConfig.java
│   ├── controller/
│   │   └── BidController.java
│   ├── model/
│   │   ├── BidRequest.java
│   │   ├── BidResponse.java
│   │   └── Campaign.java
│   └── service/
│       └── BiddingService.java
├── src/main/resources/
│   └── application.yml
├── docker-compose.yml
└── pom.xml
```

## 🚀 Quick Start

### Step 1: Create Project Directory

```bash
mkdir rtb-engine
cd rtb-engine
```

### Step 2: Copy All Files

Copy these files from the artifacts above:
- `pom.xml`
- `docker-compose.yml`
- `src/main/resources/application.yml`
- All Java files in their respective directories

### Step 3: Start Aerospike

```bash
docker-compose up -d

# Verify it's running
docker ps
```

### Step 4: Build and Run Application

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run
```

You should see:
```
╔════════════════════════════════════════════════════════╗
║                                                        ║
║              RTB ENGINE with Aerospike                 ║
║          Real-Time Bidding at Scale                    ║
║                                                        ║
╚════════════════════════════════════════════════════════╝

🎯 OpenRTB 2.5 Protocol
⚡ Sub-50ms Latency Target
💰 Real-Time Budget Tracking
📊 10K+ QPS Capability

✓ Connected to Aerospike
  Hosts: localhost:3000
  Namespace: rtb
  Cluster size: 1
```

## 🧪 Testing Phase 1

### Test 1: Health Check

```bash
curl http://localhost:8080/api/health
```

Expected response:
```json
{
  "status": "UP",
  "service": "RTB Engine",
  "phase": "1"
}
```

### Test 2: Create Test Campaign

```bash
curl -X POST http://localhost:8080/api/setup/test-campaign | jq
```

Expected response:
```json
{
  "id": "test-campaign-1",
  "name": "Phase 1 Test Campaign",
  "status": "ACTIVE",
  "bidPrice": 2.5,
  "totalBudget": 1000.0,
  "currentSpend": 0.0
}
```

### Test 3: Send First Bid Request

Create a file `test-bid-request.json`:
```json
{
  "id": "test-request-123",
  "imp": [{
    "id": "1",
    "banner": {
      "w": 300,
      "h": 250
    },
    "bidfloor": 0.5
  }],
  "site": {
    "id": "site-123",
    "domain": "example.com"
  },
  "device": {
    "ip": "192.168.1.1",
    "ua": "Mozilla/5.0...",
    "geo": {
      "country": "USA"
    }
  },
  "user": {
    "id": "user-456"
  }
}
```

Send the bid request:
```bash
curl -X POST http://localhost:8080/api/bid \
  -H "Content-Type: application/json" \
  -d @test-bid-request.json | jq
```

Expected response:
```json
{
  "id": "test-request-123",
  "seatbid": [{
    "bid": [{
      "id": "...",
      "impid": "1",
      "price": 2.5,
      "cid": "test-campaign-1",
      "crid": "creative-123",
      "adm": "<div>Test Ad</div>",
      "adomain": ["example.com"],
      "w": 300,
      "h": 250
    }]
  }],
  "cur": "USD",
  "bidid": "..."
}
```

### Test 4: Measure Latency

```bash
# Single request with timing
time curl -X POST http://localhost:8080/api/bid \
  -H "Content-Type: application/json" \
  -d @test-bid-request.json -s -o /dev/null -w "%{time_total}\n"
```

Should be under 0.05 seconds (50ms) on first try!

### Test 5: Load Test (100 requests)

```bash
# Install Apache Bench if needed
# sudo apt-get install apache2-utils

# Run 100 requests, 10 concurrent
ab -n 100 -c 10 -T 'application/json' \
  -p test-bid-request.json \
  http://localhost:8080/api/bid
```

Check the results:
- Look for "Time per request" - should be <50ms
- Look for "Requests per second" - should be high
- Look for "Failed requests" - should be 0

## 📊 What You Should See

Console output during bidding:
```
Bid processed in 12ms (Campaign: test-campaign-1, Price: $2.50)
Bid processed in 8ms (Campaign: test-campaign-1, Price: $2.50)
Bid processed in 9ms (Campaign: test-campaign-1, Price: $2.50)
```

## ✅ Phase 1 Complete!

You've successfully:
- ✅ Built OpenRTB 2.5 protocol support
- ✅ Connected to Aerospike
- ✅ Created your first campaign
- ✅ Processed bid requests
- ✅ Generated bid responses
- ✅ Measured sub-50ms latency

## 🔜 What's Next?

### Phase 2: Campaign Management
- Multiple active campaigns
- Campaign targeting rules
- Targeting evaluation logic
- Campaign selection algorithms

Ready for Phase 2? Let me know!

## 🐛 Troubleshooting

### Aerospike Won't Start
```bash
docker logs aerospike-rtb
# Check for errors

# Try restarting
docker-compose down
docker-compose up -d
```

### Application Won't Connect
```bash
# Verify Aerospike is accessible
nc -zv localhost 3000

# Check application.yml has correct host
cat src/main/resources/application.yml | grep hosts
```

### Bid Requests Failing
```bash
# Check logs for errors
# Look for Java stack traces

# Verify test campaign was created
curl -X POST http://localhost:8080/api/setup/test-campaign
```

### High Latency
- First request is always slower (JVM warmup)
- Run multiple requests to see real performance
- Check if Aerospike is running on same machine
- Monitor system resources (CPU, memory)

## 📚 Additional Resources

- [OpenRTB 2.5 Specification](https://www.iab.com/guidelines/openrtb/)
- [Aerospike Java Client Docs](https://docs.aerospike.com/client/java)
- [Spring Boot Documentation](https://spring.io/projects/spring-boot)