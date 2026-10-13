package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Scheduler logic: returns tickets of expired reservations to the available pool. */
public class ReleaseExpiredReservationsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseExpiredReservationsUseCase.class);

    private final OrderRepository orders;
    private final TicketInventory inventory;
    private final OrderMetrics metrics;
    private final Clock clock;
    private final int batchSize;

    public ReleaseExpiredReservationsUseCase(
            OrderRepository orders, TicketInventory inventory, OrderMetrics metrics, Clock clock, int batchSize) {
        this.orders = orders;
        this.inventory = inventory;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** @return number of reservations released in this run */
    public Mono<Integer> execute() {
        return orders.findExpired(clock.instant(), batchSize)
                .concatMap(this::release)
                .count()
                .map(Long::intValue)
                .doOnNext(count -> {
                    if (count > 0) {
                        metrics.reservationsExpired(count);
                    }
                });
    }

    /**
     * A concurrent confirmation (or another scheduler instance) may win the race: the conditional
     * write then fails and the order is simply skipped; the winner's outcome stands.
     */
    private Mono<Order> release(Order order) {
        return inventory.release(order).onErrorResume(
                e -> e instanceof ConcurrentUpdateException || e instanceof InvalidStateTransitionException,
                e -> {
                    LOG.debug("Skipping order {} during release: {}", order.id(), e.getMessage());
                    return Mono.empty();
                });
    }
}
