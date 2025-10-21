package com.tazifor.rtb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RTB Engine - Real-Time Bidding System with Aerospike
 *
 * A production-grade programmatic advertising platform demonstrating:
 * - OpenRTB 2.5 protocol implementation
 * - Sub-50ms bid request processing
 * - Real-time budget tracking with strong consistency
 * - Distributed frequency capping
 * - 10,000+ QPS throughput capability
 *
 * Built with Spring Boot and Aerospike for maximum performance.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class RtbEngineApplication {

    public static void main(String[] args) {
        printBanner();
        SpringApplication.run(RtbEngineApplication.class, args);
    }

    private static void printBanner() {
        System.out.println("""
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
            
            Starting RTB Engine...
            """);
    }
}
