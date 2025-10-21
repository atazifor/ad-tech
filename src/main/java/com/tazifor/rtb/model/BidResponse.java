package com.tazifor.rtb.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * OpenRTB 2.5 Bid Response
 *
 * Represents our bidding response to an ad exchange.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BidResponse {

    /**
     * ID of the bid request to which this is a response
     */
    private String id;

    /**
     * Array of seatbid objects (one per impression)
     */
    @JsonProperty("seatbid")
    private List<SeatBid> seatBids;

    /**
     * Bid currency (ISO-4217 code)
     */
    @JsonProperty("cur")
    private String currency;

    /**
     * Optional response ID to assist with logging/tracking
     */
    private String bidid;

    /**
     * Reason for not bidding (for debugging)
     */
    @JsonProperty("nbr")
    private Integer noBidReason;

    /**
     * SeatBid Object - represents bids for a seat
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatBid {
        /**
         * Array of bid objects
         */
        @JsonProperty("bid")
        private List<Bid> bids;

        /**
         * Seat ID
         */
        private String seat;
    }

    /**
     * Bid Object - individual bid for an impression
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bid {
        /**
         * Bidder-generated bid ID
         */
        private String id;

        /**
         * ID of the Imp object in the related bid request
         */
        @JsonProperty("impid")
        private String impressionId;

        /**
         * Bid price expressed as CPM (cost per thousand impressions)
         */
        private Double price;

        /**
         * Win notice URL (called by exchange when bid wins)
         */
        private String nurl;

        /**
         * Advertiser domain (for blocklist checking)
         */
        private List<String> adomain;

        /**
         * Campaign ID
         */
        @JsonProperty("cid")
        private String campaignId;

        /**
         * Creative ID
         */
        @JsonProperty("crid")
        private String creativeId;

        /**
         * Ad markup (HTML/VAST)
         */
        private String adm;

        /**
         * IAB content categories of the creative
         */
        private List<String> cat;

        /**
         * Width of creative in pixels
         */
        private Integer w;

        /**
         * Height of creative in pixels
         */
        private Integer h;
    }

    /**
     * No-Bid Reason Codes (OpenRTB 2.5 standard)
     */
    public enum NoBidReason {
        UNKNOWN_ERROR(0),
        TECHNICAL_ERROR(1),
        INVALID_REQUEST(2),
        KNOWN_WEB_SPIDER(3),
        SUSPECTED_NON_HUMAN_TRAFFIC(4),
        CLOUD_DATACENTER_PROXY(5),
        UNSUPPORTED_DEVICE(6),
        BLOCKED_PUBLISHER(7),
        UNMATCHED_USER(8),
        DAILY_READER_CAP_MET(9),
        DAILY_DOMAIN_CAP_MET(10);

        private final int code;

        NoBidReason(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /**
     * Helper method to create a no-bid response
     */
    public static BidResponse noBid(String requestId, NoBidReason reason) {
        return BidResponse.builder()
            .id(requestId)
            .noBidReason(reason.getCode())
            .build();
    }
}
