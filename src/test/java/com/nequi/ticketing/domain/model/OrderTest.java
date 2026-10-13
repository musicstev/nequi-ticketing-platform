package com.nequi.ticketing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(600);

    private Order pending(int quantity) {
        return Order.pending("ord-1", "evt-1", "cust-1", quantity, "cust-1#key", NOW);
    }

    @Test
    void pendingOrderStartsWithoutTickets() {
        Order order = pending(2);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.ticketIds()).isEmpty();
        assertThat(order.version()).isZero();
        assertThat(order.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsInvalidQuantity() {
        assertThatThrownBy(() -> pending(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void happyPathMovesThroughEveryStatus() {
        Order reserved = pending(2).reserved(List.of("T-1", "T-2"), EXPIRES, NOW);
        assertThat(reserved.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(reserved.expiresAt()).isEqualTo(EXPIRES);
        assertThat(reserved.ticketIds()).containsExactly("T-1", "T-2");

        Order awaiting = reserved.pendingConfirmation(NOW);
        assertThat(awaiting.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);

        Order confirmed = awaiting.confirmed(NOW.plusSeconds(5));
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.expiresAt()).isNull();
        assertThat(confirmed.updatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(confirmed.status().isTerminal()).isTrue();
    }

    @Test
    void reservationMustMatchRequestedQuantity() {
        assertThatThrownBy(() -> pending(2).reserved(List.of("T-1"), EXPIRES, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionIsOnlyPossibleWhilePending() {
        Order rejected = pending(1).rejected("SOLD_OUT", NOW);
        assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("SOLD_OUT");
        assertThat(rejected.status().isTerminal()).isTrue();

        Order reserved = pending(1).reserved(List.of("T-1"), EXPIRES, NOW);
        assertThatThrownBy(() -> reserved.rejected("SOLD_OUT", NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void expiryAppliesToHeldOrdersOnly() {
        Order reserved = pending(1).reserved(List.of("T-1"), EXPIRES, NOW);
        assertThat(reserved.expired(EXPIRES).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reserved.pendingConfirmation(NOW).expired(EXPIRES).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThatThrownBy(() -> pending(1).expired(EXPIRES)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> reserved.pendingConfirmation(NOW).confirmed(NOW).expired(EXPIRES))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThatThrownBy(() -> pending(1).confirmed(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> pending(1).pendingConfirmation(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        Order rejected = pending(1).rejected("SOLD_OUT", NOW);
        assertThatThrownBy(() -> rejected.reserved(List.of("T-1"), EXPIRES, NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void reservationExpiryDependsOnClock() {
        Order awaiting = pending(1).reserved(List.of("T-1"), EXPIRES, NOW).pendingConfirmation(NOW);
        assertThat(awaiting.isReservationExpired(EXPIRES.minusMillis(1))).isFalse();
        assertThat(awaiting.isReservationExpired(EXPIRES)).isTrue();
        assertThat(pending(1).isReservationExpired(EXPIRES)).isFalse();
    }

    @Test
    void ticketStatusDerivedFromOrderStatus() {
        assertThat(OrderStatus.RESERVED.heldTicketStatus()).contains(TicketStatus.RESERVED);
        assertThat(OrderStatus.PENDING_CONFIRMATION.heldTicketStatus()).contains(TicketStatus.PENDING_CONFIRMATION);
        assertThat(OrderStatus.PENDING.heldTicketStatus()).isEmpty();
        assertThat(OrderStatus.CONFIRMED.heldTicketStatus()).isEmpty();
    }

    @Test
    void withVersionReturnsCopy() {
        assertThat(pending(1).withVersion(5).version()).isEqualTo(5);
    }
}
