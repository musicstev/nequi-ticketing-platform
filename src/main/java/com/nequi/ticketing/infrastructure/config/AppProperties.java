package com.nequi.ticketing.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration. Secrets (AWS credentials, admin token) are NEVER defaulted here or in application.yml:
 * they come from the environment / task role / Secrets Manager at runtime.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Aws aws,
        Dynamodb dynamodb,
        Sqs sqs,
        Reservation reservation,
        Purchase purchase,
        RateLimit rateLimit,
        Security security,
        Duration availabilityStreamInterval) {

    public record Aws(String region) {}

    public record Dynamodb(
            String endpoint,
            boolean bootstrapTables,
            String eventsTable,
            String ticketsTable,
            String ordersTable,
            String auditTable) {}

    public record Sqs(
            String endpoint,
            String queueName,
            boolean listenerEnabled,
            int waitTimeSeconds,
            int maxMessages,
            int concurrency,
            int visibilityTimeoutSeconds,
            int processingRetries,
            int publishRetries) {}

    public record Reservation(Duration ttl, Duration releaseInterval, int releaseBatchSize, boolean schedulerEnabled) {}

    public record Purchase(int maxTicketsPerOrder) {}

    public record RateLimit(int capacity, double refillPerSecond, int maxClients, boolean trustForwardedFor) {}

    public record Security(String adminToken) {}
}
