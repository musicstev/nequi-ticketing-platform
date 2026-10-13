package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.InsufficientInventoryException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Clock;
import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * Consumer-side logic. Idempotent by construction: it dispatches on the order's persisted status, so a
 * redelivered SQS message finds the order already advanced and does nothing.
 */
public class ProcessOrderUseCase {

    static final String SOLD_OUT = "SOLD_OUT";

    private final OrderRepository orders;
    private final TicketInventory inventory;
    private final OrderMetrics metrics;
    private final Clock clock;
    private final Duration reservationTtl;

    public ProcessOrderUseCase(
            OrderRepository orders,
            TicketInventory inventory,
            OrderMetrics metrics,
            Clock clock,
            Duration reservationTtl) {
        this.orders = orders;
        this.inventory = inventory;
        this.metrics = metrics;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
    }

    public Mono<Order> execute(String orderId) {
        return orders.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMap(this::advance);
    }

    private Mono<Order> advance(Order order) {
        return switch (order.status()) {
            case PENDING -> reserve(order);
            case RESERVED -> inventory.markPendingConfirmation(order).doOnSuccess(o -> metrics.orderProcessed());
            case PENDING_CONFIRMATION, CONFIRMED, REJECTED, EXPIRED -> Mono.just(order);
        };
    }

    private Mono<Order> reserve(Order order) {
        return inventory.reserve(order, clock.instant().plus(reservationTtl))
                .flatMap(inventory::markPendingConfirmation)
                .doOnSuccess(o -> metrics.orderProcessed())
                .onErrorResume(InsufficientInventoryException.class, e -> reject(order));
    }

    private Mono<Order> reject(Order order) {
        return orders.update(order.rejected(SOLD_OUT, clock.instant())).doOnSuccess(o -> metrics.orderRejected());
    }
}
