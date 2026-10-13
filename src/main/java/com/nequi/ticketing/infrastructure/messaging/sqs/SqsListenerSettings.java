package com.nequi.ticketing.infrastructure.messaging.sqs;

import java.time.Duration;

/**
 * @param waitTimeSeconds long-poll duration (cheap, low latency)
 * @param maxMessages messages per receive call (SQS maximum is 10)
 * @param concurrency orders processed in parallel by this instance
 * @param visibilityTimeoutSeconds how long a received message stays invisible; must exceed processing time
 * @param processingRetries in-process retries for transient errors before leaving the message to SQS
 */
public record SqsListenerSettings(
        int waitTimeSeconds,
        int maxMessages,
        int concurrency,
        int visibilityTimeoutSeconds,
        int processingRetries,
        Duration retryBackoff,
        Duration pollErrorBackoff) {}
