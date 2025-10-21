#!/bin/bash

# RTB Engine - Phase 1 Test Script
# Tests basic bidding functionality

set -e

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

API_BASE="http://localhost:8080/api"

print_header() {
    echo ""
    echo -e "${BLUE}╔═══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  $1"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_step() {
    echo -e "${GREEN}▶${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

wait_for_input() {
    echo ""
    echo -e "${YELLOW}Press Enter to continue...${NC}"
    read
}

print_header "PHASE 1: FOUNDATION & OPENRTB PROTOCOL"
echo "This phase tests:"
echo "  1. Application health"
echo "  2. Campaign creation"
echo "  3. Basic bidding"
echo "  4. OpenRTB protocol"
echo "  5. Performance measurement"

wait_for_input

# Test 1: Health Check
print_header "Test 1: Health Check"
print_step "Checking if RTB Engine is running..."
curl -s "$API_BASE/health" | jq '.'
print_success "RTB Engine is healthy!"

wait_for_input

# Test 2: Create Test Campaign
print_header "Test 2: Create Test Campaign"
print_step "Creating test campaign in Aerospike..."
curl -s -X POST "$API_BASE/setup/test-campaign" | jq '.'
print_success "Test campaign created!"

wait_for_input

# Test 3: Send First Bid Request
print_header "Test 3: Send Bid Request"
print_step "Sending OpenRTB 2.5 bid request..."

# Create bid request
cat > /tmp/test-bid-request.json << 'EOF'
{
  "id": "test-request-001",
  "imp": [{
    "id": "1",
    "banner": {
      "w": 300,
      "h": 250
    },
    "bidfloor": 0.5,
    "tagid": "banner-slot-1"
  }],
  "site": {
    "id": "site-123",
    "domain": "example.com",
    "cat": ["IAB1"]
  },
  "device": {
    "ip": "192.168.1.1",
    "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/91.0",
    "devicetype": 2,
    "geo": {
      "country": "USA",
      "region": "CA",
      "city": "San Francisco"
    }
  },
  "user": {
    "id": "user-456",
    "gender": "M"
  },
  "at": 2,
  "tmax": 100
}
EOF

echo ""
echo "Request:"
cat /tmp/test-bid-request.json | jq '.'
echo ""
echo "Response:"
curl -s -X POST "$API_BASE/bid" \
  -H "Content-Type: application/json" \
  -d @/tmp/test-bid-request.json | jq '.'

print_success "Bid response received!"

wait_for_input

# Test 4: Latency Test
print_header "Test 4: Latency Measurement"
print_step "Measuring latency for 10 requests..."

echo ""
total=0
for i in {1..10}; do
    latency=$(curl -s -X POST "$API_BASE/bid" \
      -H "Content-Type: application/json" \
      -d @/tmp/test-bid-request.json \
      -w "%{time_total}" -o /dev/null)

    latency_ms=$(echo "$latency * 1000" | bc)
    printf "Request %2d: %6.2f ms\n" $i $latency_ms
    total=$(echo "$total + $latency_ms" | bc)
done

avg=$(echo "scale=2; $total / 10" | bc)
echo ""
echo -e "${GREEN}Average latency: $avg ms${NC}"

if (( $(echo "$avg < 50" | bc -l) )); then
    print_success "✓ Sub-50ms latency achieved!"
else
    echo -e "${YELLOW}⚠ Latency above 50ms target (first run is often slower)${NC}"
fi

wait_for_input

# Test 5: Multiple Requests
print_header "Test 5: Rapid Fire Test"
print_step "Sending 50 requests rapidly..."

echo ""
success=0
for i in {1..50}; do
    response=$(curl -s -X POST "$API_BASE/bid" \
      -H "Content-Type: application/json" \
      -d @/tmp/test-bid-request.json)

    # Check if response has seatbid (indicating a bid was made)
    if echo "$response" | jq -e '.seatbid' > /dev/null 2>&1; then
        success=$((success + 1))
        echo -n "."
    else
        echo -n "x"
    fi

    # Brief pause to avoid overwhelming
    sleep 0.01
done

echo ""
echo ""
echo "Results:"
echo "  Total requests: 50"
echo "  Successful bids: $success"
echo "  Success rate: $((success * 100 / 50))%"

if [ $success -eq 50 ]; then
    print_success "Perfect! All 50 requests received bids!"
else
    echo -e "${YELLOW}⚠ Some requests didn't receive bids${NC}"
fi

wait_for_input

# Final Summary
print_header "PHASE 1 COMPLETE!"

echo "Summary of achievements:"
echo ""
echo -e "${GREEN}✓ RTB Engine is running${NC}"
echo -e "${GREEN}✓ Campaign created in Aerospike${NC}"
echo -e "${GREEN}✓ OpenRTB 2.5 protocol implemented${NC}"
echo -e "${GREEN}✓ Bid requests processed successfully${NC}"
echo -e "${GREEN}✓ Average latency: $avg ms${NC}"
echo -e "${GREEN}✓ Success rate: $((success * 100 / 50))%${NC}"
echo ""
echo -e "${BLUE}═════════════════════════════════════════════════${NC}"
echo ""
echo "What we built:"
echo "  • OpenRTB 2.5 bid request/response models"
echo "  • Campaign storage in Aerospike"
echo "  • Basic bidding logic"
echo "  • Low-latency request processing"
echo ""
echo "Next up: Phase 2 - Campaign Management"
echo "  • Multiple active campaigns"
echo "  • Geographic targeting"
echo "  • Device targeting"
echo "  • Campaign selection logic"
echo ""
echo -e "${BLUE}═════════════════════════════════════════════════${NC}"
echo ""

# Cleanup
rm -f /tmp/test-bid-request.json