package com.nequi.ticketing.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Throttles the business API per client; actuator and other paths are exempt. */
public class RateLimitWebFilter implements WebFilter, Ordered {

    private static final String API_PREFIX = "/api/";

    private final TokenBucketRateLimiter limiter;
    private final boolean trustForwardedFor;

    public RateLimitWebFilter(TokenBucketRateLimiter limiter, boolean trustForwardedFor) {
        this.limiter = limiter;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(API_PREFIX)) {
            return chain.filter(exchange);
        }
        TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(clientKey(exchange));
        if (decision.allowed()) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = """
                {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Rate limit exceeded, retry later"}"""
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * Behind a trusted load balancer the connecting address is the balancer, so the LAST X-Forwarded-For entry
     * (the one the balancer itself appended) identifies the client. Leftmost entries are client-controlled.
     */
    private String clientKey(ServerWebExchange exchange) {
        if (trustForwardedFor) {
            List<String> forwarded = exchange.getRequest().getHeaders().get("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                String[] hops = forwarded.getLast().split(",");
                return hops[hops.length - 1].trim();
            }
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
