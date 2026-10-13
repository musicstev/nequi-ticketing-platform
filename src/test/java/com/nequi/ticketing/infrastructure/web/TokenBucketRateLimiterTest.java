package com.nequi.ticketing.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock, 3, 1.0, 100);

    @Test
    void allowsABurstUpToCapacityThenRejectsWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTimeButNeverBeyondCapacity() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.advance(Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void clientsAreIsolatedFromEachOther() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void memoryStaysBoundedWhenManyDistinctClientsAppear() {
        TokenBucketRateLimiter small = new TokenBucketRateLimiter(clock, 2, 1.0, 5);
        for (int i = 0; i < 50; i++) {
            small.tryAcquire("client-" + i);
        }
        assertThat(small.trackedClients()).isLessThanOrEqualTo(6);

        clock.advance(Duration.ofMinutes(5)); // idle buckets are full again and can be purged
        small.tryAcquire("fresh");
        assertThat(small.trackedClients()).isLessThanOrEqualTo(2);
    }
}
