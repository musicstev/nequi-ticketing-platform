package com.nequi.ticketing.domain.model;

import java.util.Optional;

public enum OrderStatus {
    /** Accepted and queued, not yet processed by the consumer. */
    PENDING,
    /** Tickets reserved (temporary hold), processing not finished. */
    RESERVED,
    /** Tickets held and waiting for the customer's confirmation. */
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    EXPIRED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == RESERVED || target == REJECTED;
            case RESERVED -> target == PENDING_CONFIRMATION || target == EXPIRED;
            case PENDING_CONFIRMATION -> target == CONFIRMED || target == EXPIRED;
            case CONFIRMED, REJECTED, EXPIRED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == REJECTED || this == EXPIRED;
    }

    /** Status the order's tickets are in while the order has them on hold. */
    public Optional<TicketStatus> heldTicketStatus() {
        return switch (this) {
            case RESERVED -> Optional.of(TicketStatus.RESERVED);
            case PENDING_CONFIRMATION -> Optional.of(TicketStatus.PENDING_CONFIRMATION);
            default -> Optional.empty();
        };
    }
}
