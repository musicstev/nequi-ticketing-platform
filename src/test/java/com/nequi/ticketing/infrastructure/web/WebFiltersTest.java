package com.nequi.ticketing.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class WebFiltersTest {

    @RestController
    static class Ping {
        @GetMapping("/api/v1/ping")
        Mono<String> api() {
            return Mono.just("pong");
        }

        @GetMapping("/actuator/health")
        Mono<String> health() {
            return Mono.just("UP");
        }
    }

    private WebTestClient client(boolean trustForwardedFor) {
        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC), 2, 0.001, 100);
        return WebTestClient.bindToController(new Ping())
                .webFilter(new CorrelationIdWebFilter(), new RateLimitWebFilter(limiter, trustForwardedFor))
                .build();
    }

    @Test
    void rejectsRequestsOverTheRateLimitWith429AndRetryAfter() {
        WebTestClient client = client(false);
        client.get().uri("/api/v1/ping").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/ping").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/ping").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.status").isEqualTo(429);
    }

    @Test
    void operationalEndpointsAreNotRateLimited() {
        WebTestClient client = client(false);
        for (int i = 0; i < 10; i++) {
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }

    @Test
    void forwardedForIsOnlyTrustedWhenExplicitlyEnabledAndUsesTheLastHop() {
        WebTestClient trusting = client(true);
        for (int i = 0; i < 2; i++) {
            trusting.get().uri("/api/v1/ping").header("X-Forwarded-For", "6.6.6." + i + ", 10.0.0.1").exchange()
                    .expectStatus().isOk();
        }
        // Spoofed leftmost values do not buy extra quota: the proxy-appended last hop is the key.
        trusting.get().uri("/api/v1/ping").header("X-Forwarded-For", "7.7.7.7, 10.0.0.1").exchange()
                .expectStatus().isEqualTo(429);
        trusting.get().uri("/api/v1/ping").header("X-Forwarded-For", "7.7.7.7, 10.0.0.2").exchange()
                .expectStatus().isOk();

        WebTestClient strict = client(false);
        for (int i = 0; i < 2; i++) {
            strict.get().uri("/api/v1/ping").header("X-Forwarded-For", "9.9.9." + i).exchange().expectStatus().isOk();
        }
        strict.get().uri("/api/v1/ping").header("X-Forwarded-For", "9.9.9.9").exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void echoesAValidCorrelationIdAndGeneratesOneOtherwise() {
        WebTestClient client = client(false);
        client.get().uri("/api/v1/ping").header("X-Correlation-Id", "abc-123").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "abc-123")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");

        String generated = client.get().uri("/actuator/health").header("X-Correlation-Id", "bad value\r\nInjected: 1")
                .exchange().returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(generated).matches("[0-9a-f-]{36}");

        String missing = client.get().uri("/actuator/health").exchange().returnResult(String.class)
                .getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(missing).isNotBlank().isNotEqualTo(generated);
    }
}
