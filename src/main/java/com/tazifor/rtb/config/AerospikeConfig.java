package com.tazifor.rtb.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.WritePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Aerospike Configuration for RTB Engine
 *
 * Configures Aerospike client optimized for low-latency RTB workloads
 */
@Configuration
public class AerospikeConfig {

    @Value("${aerospike.hosts}")
    private String hosts;

    @Value("${aerospike.namespace}")
    private String namespace;

    @Bean
    public AerospikeClient aerospikeClient() {
        ClientPolicy policy = new ClientPolicy();

        // Optimize for RTB workloads
        policy.maxConnsPerNode = 300;  // High concurrency
        policy.connPoolsPerNode = 1;
        // 50ms timeout to match RTB requirements
        policy.readPolicyDefault.totalTimeout = 50;
        policy.writePolicyDefault.totalTimeout = 50;

        // Parse hosts
        List<Host> hostList = new ArrayList<>();
        String[] hostParts = hosts.split(",");
        for (String hostPart : hostParts) {
            String[] parts = hostPart.trim().split(":");
            String hostname = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 3000;
            hostList.add(new Host(hostname, port));
        }

        AerospikeClient client = new AerospikeClient(policy,
            hostList.toArray(new Host[0]));

        System.out.println("✓ Connected to Aerospike");
        System.out.println("  Hosts: " + hosts);
        System.out.println("  Namespace: " + namespace);
        System.out.println("  Cluster size: " + client.getNodes().length);

        return client;
    }

    @Bean
    public String aerospikeNamespace() {
        return namespace;
    }

    /**
     * Write policy for budget operations (strong consistency required)
     */
    @Bean("budgetWritePolicy")
    public WritePolicy budgetWritePolicy() {
        WritePolicy policy = new WritePolicy();
        policy.totalTimeout = 50;
        policy.sendKey = true;
        // Strong consistency for budget tracking
        policy.commitLevel = com.aerospike.client.policy.CommitLevel.COMMIT_MASTER;
        return policy;
    }

    /**
     * Write policy for regular operations
     */
    @Bean("defaultWritePolicy")
    public WritePolicy defaultWritePolicy() {
        WritePolicy policy = new WritePolicy();
        policy.totalTimeout = 50;
        policy.sendKey = true;
        return policy;
    }
}
