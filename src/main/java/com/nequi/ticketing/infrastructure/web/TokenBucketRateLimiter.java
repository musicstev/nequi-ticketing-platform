package com.nequi.ticketing.infrastructure.web;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per client key. Good enough to blunt abuse of a single instance; a
 * shared limiter (API Gateway / WAF rate rules) is the production complement (see README).
 * Memory is bounded: when {@code maxClients} is reached, idle (full) buckets are purged and,
 * if none can be, unknown clients share one overflow bucket.
 */
public class TokenBucketRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private final Clock clock;
    private final double capacity;
    private final double refillPerSecond;
    private final int maxClients;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Bucket overflow;

    public TokenBucketRateLimiter(Clock clock, int capacity, double refillPerSecond, int maxClients) {
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxClients = maxClients;
        this.overflow = new Bucket(capacity, clock.instant());
    }

    public Decision tryAcquire(String clientKey) {
        Instant now = clock.instant();
        Bucket bucket = buckets.get(clientKey);
        if (bucket == null) {
            if (buckets.size() >= maxClients) {
                purgeIdle(now);
            }
            bucket = buckets.size() >= maxClients
                    ? overflow
                    : buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, now));
        }
        return bucket.take(now);
    }

    int trackedClients() {
        return buckets.size();
    }

    private void purgeIdle(Instant now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().isFull(now));
    }

    private final class Bucket {
        private double tokens;
        private Instant lastRefill;

        Bucket(double tokens, Instant lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }

        synchronized Decision take(Instant now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            long wait = (long) Math.ceil((1 - tokens) / refillPerSecond);
            return new Decision(false, Math.max(1, wait));
        }

        synchronized boolean isFull(Instant now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(Instant now) {
            double elapsedSeconds = Math.max(0, (now.toEpochMilli() - lastRefill.toEpochMilli()) / 1000.0);
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefill = now;
        }
    }
}
