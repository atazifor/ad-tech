package com.tazifor.rtb.controller;

import com.tazifor.rtb.model.BidRequest;
import com.tazifor.rtb.model.BidResponse;
import com.tazifor.rtb.service.BiddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * BidController - Phase 1: OpenRTB Bidding Endpoint
 *
 * Implements the /bid endpoint that receives bid requests from ad exchanges
 * and returns bid responses following OpenRTB 2.5 protocol.
 */
@RestController
@RequestMapping("/api")
public class BidController {

    @Autowired
    private BiddingService biddingService;

    /**
     * Main bidding endpoint - OpenRTB 2.5
     *
     * POST /api/bid
     * Content-Type: application/json
     *
     * Accepts OpenRTB bid request, processes it, and returns bid response.
     */
    @PostMapping("/bid")
    public ResponseEntity<BidResponse> handleBidRequest(@RequestBody BidRequest request) {
        long startTime = System.nanoTime();

        try {
            // Process the bid request
            BidResponse response = biddingService.processBidRequest(request);

            // Calculate latency
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;

            // Add latency header for monitoring
            return ResponseEntity.ok()
                .header("X-Processing-Time-Ms", String.valueOf(latencyMs))
                .body(response);

        } catch (Exception e) {
            System.err.println("Error in bid controller: " + e.getMessage());
            e.printStackTrace();

            // Return no-bid on error
            BidResponse errorResponse = BidResponse.noBid(
                request.getId(),
                BidResponse.NoBidReason.TECHNICAL_ERROR
            );

            return ResponseEntity.status(HttpStatus.OK).body(errorResponse);
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "RTB Engine",
            "phase", "1"
        ));
    }

}
