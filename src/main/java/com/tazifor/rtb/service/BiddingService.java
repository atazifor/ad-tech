package com.tazifor.rtb.service;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.tazifor.rtb.model.BidRequest;
import com.tazifor.rtb.model.BidResponse;
import com.tazifor.rtb.model.Campaign;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BiddingService - Phase 1: Basic Bidding Logic
 *
 * Handles incoming bid requests and generates bid responses.
 * Phase 1 implements single-campaign bidding without advanced targeting.
 */
@Service
public class BiddingService {

    @Autowired
    private AerospikeClient client;

    @Value("${aerospike.namespace}")
    private String namespace;

    private static final String CAMPAIGN_SET = "campaigns";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Process a bid request and generate a bid response
     *
     * Phase 1: Simple logic - find any active campaign and bid
     */
    public BidResponse processBidRequest(BidRequest request) {
        long startTime = System.nanoTime();

        try {
            // Validate request
            if (request.getImpressions() == null || request.getImpressions().isEmpty()) {
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.INVALID_REQUEST);
            }

            // Get active campaigns (Phase 1: just get first active one)
            List<Campaign> campaigns = getActiveCampaigns();
            if (campaigns.isEmpty()) {
                return BidResponse.noBid(request.getId(),
                    BidResponse.NoBidReason.UNMATCHED_USER);
            }

            // For Phase 1, use first active campaign
            Campaign campaign = campaigns.get(0);

            // Build bid response
            List<BidResponse.SeatBid> seatBids = new ArrayList<>();
            List<BidResponse.Bid> bids = new ArrayList<>();

            // Bid on first impression
            BidRequest.Impression imp = request.getImpressions().get(0);

            BidResponse.Bid bid = BidResponse.Bid.builder()
                .id(UUID.randomUUID().toString())
                .impressionId(imp.getId())
                .price(campaign.getEffectiveBidPrice())
                .campaignId(campaign.getId())
                .creativeId(campaign.getCreativeId())
                .adm(campaign.getAdMarkup())
                .adomain(campaign.getAdvertiserDomains())
                .build();

            // Set creative dimensions if banner
            if (imp.getBanner() != null) {
                bid.setW(imp.getBanner().getW());
                bid.setH(imp.getBanner().getH());
            }

            bids.add(bid);

            BidResponse.SeatBid seatBid = BidResponse.SeatBid.builder()
                .bids(bids)
                .build();

            seatBids.add(seatBid);

            BidResponse response = BidResponse.builder()
                .id(request.getId())
                .seatBids(seatBids)
                .currency("USD")
                .bidid(UUID.randomUUID().toString())
                .build();

            // Log performance
            long latency = (System.nanoTime() - startTime) / 1_000_000;
            System.out.printf("Bid processed in %dms (Campaign: %s, Price: $%.2f)%n",
                latency, campaign.getId(), bid.getPrice());

            return response;

        } catch (Exception e) {
            System.err.println("Error processing bid request: " + e.getMessage());
            return BidResponse.noBid(request.getId(),
                BidResponse.NoBidReason.TECHNICAL_ERROR);
        }
    }

    /**
     * Get active campaigns from Aerospike
     * Phase 1: Simple scan, no filtering
     */
    private List<Campaign> getActiveCampaigns() {
        List<Campaign> campaigns = new ArrayList<>();

        try {
            // In Phase 1, we'll just try to get a specific campaign by ID
            // Later phases will implement proper campaign selection
            Key key = new Key(namespace, CAMPAIGN_SET, "test-campaign-1");
            Record record = client.get(null, key);

            if (record != null) {
                Campaign campaign = recordToCampaign(record);
                if (campaign != null && campaign.canBid()) {
                    campaigns.add(campaign);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching campaigns: " + e.getMessage());
        }

        return campaigns;
    }

    /**
     * Convert Aerospike record to Campaign object
     */
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
     * Create a test campaign for Phase 1 demo
     */
    public Campaign createTestCampaign() {
        Campaign campaign = Campaign.builder()
            .id("test-campaign-1")
            .name("Phase 1 Test Campaign")
            .advertiserId("advertiser-1")
            .status(Campaign.CampaignStatus.ACTIVE)
            .bidPrice(2.50) // $2.50 CPM
            .totalBudget(1000.0)
            .currentSpend(0.0)
            .creativeId("creative-123")
            .adMarkup("<div>Test Ad</div>")
            .advertiserDomains(List.of("example.com"))
            .impressions(0L)
            .clicks(0L)
            .build();

        try {
            Key key = new Key(namespace, CAMPAIGN_SET, campaign.getId());
            String json = objectMapper.writeValueAsString(campaign);

            Bin dataBin = new Bin("data", json);
            Bin idBin = new Bin("id", campaign.getId());
            Bin statusBin = new Bin("status", campaign.getStatus().toString());

            client.put(null, key, dataBin, idBin, statusBin);

            System.out.println("✓ Created test campaign: " + campaign.getId());
            return campaign;

        } catch (Exception e) {
            System.err.println("Error creating test campaign: " + e.getMessage());
            return null;
        }
    }
}
