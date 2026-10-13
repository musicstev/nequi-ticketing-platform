package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;

/**
 * Ticket lifecycle. A ticket holds exactly one status at a time.
 *
 * <pre>
 * AVAILABLE -> RESERVED -> PENDING_CONFIRMATION -> SOLD (final, accountable)
 *     |            |                |
 *     |            +----------------+--> AVAILABLE (reservation expired)
 *     +--> COMPLIMENTARY (final, not accountable)
 * </pre>
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case AVAILABLE -> target == RESERVED || target == COMPLIMENTARY;
            case RESERVED -> target == PENDING_CONFIRMATION || target == AVAILABLE;
            case PENDING_CONFIRMATION -> target == SOLD || target == AVAILABLE;
            case SOLD, COMPLIMENTARY -> false;
        };
    }

    public void requireTransitionTo(TicketStatus target) {
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException("ticket", this, target);
        }
    }

    public boolean isFinal() {
        return this == SOLD || this == COMPLIMENTARY;
    }

    /** Only SOLD is an accountable sale; holds are temporary and complimentary tickets are not revenue. */
    public boolean isSale() {
        return this == SOLD;
    }

    public boolean isHold() {
        return this == RESERVED || this == PENDING_CONFIRMATION;
    }
}
