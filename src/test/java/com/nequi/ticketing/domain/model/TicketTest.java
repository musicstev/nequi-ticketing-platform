package com.nequi.ticketing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TicketTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(600);

    @Test
    void newAvailableTicketHasStableFormattedId() {
        Ticket ticket = Ticket.newAvailable("evt-1", 7);
        assertThat(ticket.ticketId()).isEqualTo("T-000007");
        assertThat(ticket.status()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(ticket.orderId()).isNull();
        assertThat(ticket.version()).isZero();
    }

    @Test
    void reservingAssignsOrderAndExpiryAndAuditsTheTransition() {
        Ticket.Change change = Ticket.newAvailable("evt-1", 1)
                .transitionTo(TicketStatus.RESERVED, "ord-1", EXPIRES, TransitionReason.RESERVATION_REQUESTED, NOW);

        assertThat(change.ticket().status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(change.ticket().orderId()).isEqualTo("ord-1");
        assertThat(change.ticket().expiresAt()).isEqualTo(EXPIRES);
        assertThat(change.ticket().version()).isEqualTo(1);
        assertThat(change.transition()).isEqualTo(new TicketTransition(
                "evt-1", "T-000001", "ord-1", TicketStatus.AVAILABLE, TicketStatus.RESERVED,
                TransitionReason.RESERVATION_REQUESTED, NOW));
    }

    @Test
    void reservingWithoutOrderOrExpiryIsRejected() {
        Ticket available = Ticket.newAvailable("evt-1", 1);
        assertThatThrownBy(() -> available.transitionTo(
                TicketStatus.RESERVED, null, EXPIRES, TransitionReason.RESERVATION_REQUESTED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> available.transitionTo(
                TicketStatus.RESERVED, "ord-1", null, TransitionReason.RESERVATION_REQUESTED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullPurchaseLifecycleEndsInSoldWithoutExpiry() {
        Ticket sold = Ticket.newAvailable("evt-1", 1)
                .transitionTo(TicketStatus.RESERVED, "ord-1", EXPIRES, TransitionReason.RESERVATION_REQUESTED, NOW)
                .ticket()
                .transitionTo(TicketStatus.PENDING_CONFIRMATION, null, null, TransitionReason.PROCESSING_COMPLETED, NOW)
                .ticket()
                .transitionTo(TicketStatus.SOLD, null, null, TransitionReason.PURCHASE_CONFIRMED, NOW)
                .ticket();

        assertThat(sold.status()).isEqualTo(TicketStatus.SOLD);
        assertThat(sold.orderId()).isEqualTo("ord-1");
        assertThat(sold.expiresAt()).isNull();
        assertThat(sold.version()).isEqualTo(3);
    }

    @Test
    void releasingClearsOrderAndExpiry() {
        Ticket released = Ticket.newAvailable("evt-1", 1)
                .transitionTo(TicketStatus.RESERVED, "ord-1", EXPIRES, TransitionReason.RESERVATION_REQUESTED, NOW)
                .ticket()
                .transitionTo(TicketStatus.AVAILABLE, null, null, TransitionReason.RESERVATION_EXPIRED, EXPIRES)
                .ticket();

        assertThat(released.status()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(released.orderId()).isNull();
        assertThat(released.expiresAt()).isNull();
    }

    @Test
    void complimentaryTicketIsFinalAndNeverBelongsToAnOrder() {
        Ticket complimentary = Ticket.newAvailable("evt-1", 1)
                .transitionTo(TicketStatus.COMPLIMENTARY, null, null, TransitionReason.COMPLIMENTARY_GRANT, NOW)
                .ticket();

        assertThat(complimentary.orderId()).isNull();
        assertThatThrownBy(() -> complimentary.transitionTo(
                TicketStatus.AVAILABLE, null, null, TransitionReason.RESERVATION_EXPIRED, NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void soldIsIrreversible() {
        Ticket sold = new Ticket("evt-1", "T-000001", TicketStatus.SOLD, "ord-1", null, 3);
        for (TicketStatus target : TicketStatus.values()) {
            assertThatThrownBy(() -> sold.transitionTo(target, "ord-2", EXPIRES, TransitionReason.RESERVATION_REQUESTED, NOW))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Test
    void holdExpiresOnlyAfterItsDeadline() {
        Ticket reserved = new Ticket("evt-1", "T-000001", TicketStatus.RESERVED, "ord-1", EXPIRES, 1);
        assertThat(reserved.isExpiredAt(EXPIRES.minusSeconds(1))).isFalse();
        assertThat(reserved.isExpiredAt(EXPIRES)).isTrue();
        assertThat(Ticket.newAvailable("evt-1", 1).isExpiredAt(EXPIRES)).isFalse();
        assertThat(new Ticket("evt-1", "T-1", TicketStatus.RESERVED, "o", null, 1).isExpiredAt(EXPIRES)).isFalse();
    }
}
