package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import java.util.List;

/**
 * Purchase order. Immutable: every transition returns a new instance. {@code version} is the
 * optimistic-locking token owned by the persistence adapter.
 */
public record Order(
        String id,
        String eventId,
        String customerId,
        int quantity,
        OrderStatus status,
        List<String> ticketIds,
        Instant expiresAt,
        String rejectionReason,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Order {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        ticketIds = ticketIds == null ? List.of() : List.copyOf(ticketIds);
    }

    public static Order pending(
            String id, String eventId, String customerId, int quantity, String idempotencyKey, Instant now) {
        return new Order(id, eventId, customerId, quantity, OrderStatus.PENDING, List.of(), null, null,
                idempotencyKey, now, now, 0);
    }

    public Order reserved(List<String> reservedTicketIds, Instant reservationExpiry, Instant now) {
        requireTransitionTo(OrderStatus.RESERVED);
        if (reservedTicketIds.size() != quantity) {
            throw new IllegalArgumentException("Reserved tickets must match the requested quantity");
        }
        return copy(OrderStatus.RESERVED, reservedTicketIds, reservationExpiry, null, now);
    }

    public Order pendingConfirmation(Instant now) {
        requireTransitionTo(OrderStatus.PENDING_CONFIRMATION);
        return copy(OrderStatus.PENDING_CONFIRMATION, ticketIds, expiresAt, null, now);
    }

    public Order confirmed(Instant now) {
        requireTransitionTo(OrderStatus.CONFIRMED);
        return copy(OrderStatus.CONFIRMED, ticketIds, null, null, now);
    }

    public Order rejected(String reason, Instant now) {
        requireTransitionTo(OrderStatus.REJECTED);
        return copy(OrderStatus.REJECTED, ticketIds, null, reason, now);
    }

    public Order expired(Instant now) {
        requireTransitionTo(OrderStatus.EXPIRED);
        return copy(OrderStatus.EXPIRED, ticketIds, null, null, now);
    }

    public Order withVersion(long newVersion) {
        return new Order(id, eventId, customerId, quantity, status, ticketIds, expiresAt, rejectionReason,
                idempotencyKey, createdAt, updatedAt, newVersion);
    }

    public boolean isReservationExpired(Instant now) {
        return status.heldTicketStatus().isPresent() && expiresAt != null && !expiresAt.isAfter(now);
    }

    private void requireTransitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("order", status, target);
        }
    }

    private Order copy(OrderStatus next, List<String> tickets, Instant expiry, String reason, Instant now) {
        return new Order(id, eventId, customerId, quantity, next, tickets, expiry, reason, idempotencyKey,
                createdAt, now, version);
    }
}
