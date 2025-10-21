package com.tazifor.rtb.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * OpenRTB 2.5 Bid Request
 *
 * Represents an incoming bid request from an ad exchange.
 * Simplified version containing the most important fields.
 */
@Data
public class BidRequest {

    /**
     * Unique ID of the bid request
     */
    private String id;

    /**
     * Array of impression objects
     */
    @JsonProperty("imp")
    private List<Impression> impressions;

    /**
     * Site object (for display ads)
     */
    private Site site;

    /**
     * App object (for mobile ads)
     */
    private App app;

    /**
     * Device information
     */
    private Device device;

    /**
     * User information
     */
    private User user;

    /**
     * Auction type: 1 = first price, 2 = second price
     */
    private Integer at;

    /**
     * Maximum time in milliseconds to submit a bid
     */
    private Integer tmax;

    /**
     * Timestamp when request was sent (Unix epoch time in ms)
     */
    private Long timestamp;

    /**
     * Impression Object
     */
    @Data
    public static class Impression {
        private String id;
        private Banner banner;
        private Video video;

        @JsonProperty("bidfloor")
        private Double bidFloor; // Minimum bid price

        @JsonProperty("bidfloorcur")
        private String bidFloorCurrency;

        private String tagid; // Ad tag/placement ID
    }

    /**
     * Banner Object
     */
    @Data
    public static class Banner {
        private Integer w; // Width
        private Integer h; // Height
        private List<Integer> battr; // Blocked creative attributes
    }

    /**
     * Video Object
     */
    @Data
    public static class Video {
        private List<String> mimes;
        private Integer minduration;
        private Integer maxduration;
        private List<Integer> protocols;
    }

    /**
     * Site Object (for web traffic)
     */
    @Data
    public static class Site {
        private String id;
        private String name;
        private String domain;
        private List<String> cat; // IAB content categories
        private Publisher publisher;
    }

    /**
     * App Object (for mobile traffic)
     */
    @Data
    public static class App {
        private String id;
        private String name;
        private String bundle;
        private List<String> cat;
        private Publisher publisher;
    }

    /**
     * Publisher Object
     */
    @Data
    public static class Publisher {
        private String id;
        private String name;
    }

    /**
     * Device Object
     */
    @Data
    public static class Device {
        private String ua; // User agent
        private Geo geo;
        private String ip;
        private String ipv6;

        @JsonProperty("devicetype")
        private Integer deviceType; // 1=mobile, 2=PC, 3=TV, etc.

        private String make;
        private String model;
        private String os;
        private String osv;
        private Integer w; // Screen width
        private Integer h; // Screen height
    }

    /**
     * Geo Object
     */
    @Data
    public static class Geo {
        private Double lat;
        private Double lon;
        private String country;
        private String region;
        private String city;
        private String zip;
    }

    /**
     * User Object
     */
    @Data
    public static class User {
        private String id; // User ID
        private String buyeruid; // Buyer-specific user ID
        private Integer yob; // Year of birth
        private String gender; // M or F
    }
}
