package com.nequi.ticketing.domain.port;

import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Inventory operations. Every method that changes tickets is atomic with the order update it
 * accompanies (tickets, event counters, audit trail and order move together or not at all) and
 * must be implemented with conditional writes so that concurrent callers can never oversell.
 */
public interface TicketInventory {

    Mono<Availability> availability(String eventId);

    /** PENDING order: AVAILABLE -> RESERVED for {@code order.quantity()} tickets; order becomes RESERVED. */
    Mono<Order> reserve(Order order, Instant expiresAt);

    /** RESERVED -> PENDING_CONFIRMATION for the order's tickets; order becomes PENDING_CONFIRMATION. */
    Mono<Order> markPendingConfirmation(Order order);

    /** PENDING_CONFIRMATION -> SOLD (final); order becomes CONFIRMED. */
    Mono<Order> confirmSale(Order order);

    /** Held tickets -> AVAILABLE; order becomes EXPIRED. */
    Mono<Order> release(Order order);

    Mono<List<Ticket>> ticketsOf(Order order);

    /** AVAILABLE -> COMPLIMENTARY (final, not accountable) for {@code quantity} tickets. */
    Mono<List<Ticket>> grantComplimentary(String eventId, int quantity, Instant now);
}
