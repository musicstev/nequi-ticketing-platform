package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.exception.ReservationExpiredException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Clock;
import reactor.core.publisher.Mono;

public class ConfirmPurchaseUseCase {

    private final OrderRepository orders;
    private final TicketInventory inventory;
    private final OrderMetrics metrics;
    private final Clock clock;

    public ConfirmPurchaseUseCase(
            OrderRepository orders, TicketInventory inventory, OrderMetrics metrics, Clock clock) {
        this.orders = orders;
        this.inventory = inventory;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<Order> execute(String orderId) {
        return orders.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMap(this::confirm);
    }

    private Mono<Order> confirm(Order order) {
        return switch (order.status()) {
            case CONFIRMED -> Mono.just(order);
            case EXPIRED -> Mono.error(new ReservationExpiredException(order.id()));
            case PENDING_CONFIRMATION -> order.isReservationExpired(clock.instant())
                    ? Mono.error(new ReservationExpiredException(order.id()))
                    : inventory.confirmSale(order).doOnSuccess(o -> metrics.orderConfirmed());
            case PENDING, RESERVED, REJECTED -> Mono.error(new InvalidStateTransitionException(
                    "Order " + order.id() + " cannot be confirmed while " + order.status()));
        };
    }
}
