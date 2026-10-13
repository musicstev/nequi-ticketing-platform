package com.nequi.ticketing.domain.port;

import com.nequi.ticketing.domain.model.Order;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderRepository {

    /** Result of an idempotent creation: the stored order and whether this call created it. */
    record CreationResult(Order order, boolean created) {}

    /**
     * Stores the order unless one already exists for the same idempotency key, in which case the
     * existing order is returned. Must be atomic.
     */
    Mono<CreationResult> createIfAbsent(Order order);

    Mono<Order> findById(String orderId);

    /**
     * Optimistic update: succeeds only if the stored version equals {@code order.version()}, and
     * returns the order with the incremented version. Fails with ConcurrentUpdateException otherwise.
     */
    Mono<Order> update(Order order);

    /** Orders holding tickets (RESERVED / PENDING_CONFIRMATION) whose reservation expired at {@code now}. */
    Flux<Order> findExpired(Instant now, int limit);
}
