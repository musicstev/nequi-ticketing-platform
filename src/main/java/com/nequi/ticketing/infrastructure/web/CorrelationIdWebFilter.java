package com.nequi.ticketing.infrastructure.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Propagates (or creates) a correlation id and sets baseline hardening headers. Client-provided ids are
 * accepted only if they are short and alphanumeric, which prevents log/header injection.
 * Request logs carry method, path and status only: never headers, query strings or bodies.
 */
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    static final String HEADER = "X-Correlation-Id";
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdWebFilter.class);
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = supplied != null && VALID_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        var headers = exchange.getResponse().getHeaders();
        headers.set(HEADER, correlationId);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        return chain.filter(exchange)
                .doFinally(signal -> LOG.info(
                        "{} {} -> {} [correlationId={}]",
                        exchange.getRequest().getMethod(),
                        exchange.getRequest().getPath().value(),
                        exchange.getResponse().getStatusCode(),
                        correlationId));
    }
}
