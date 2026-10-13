package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.CLOCK;
import static com.nequi.ticketing.application.TestFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.exception.ReservationExpiredException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ConfirmPurchaseUseCaseTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final OrderMetrics metrics = mock(OrderMetrics.class);
    private final ConfirmPurchaseUseCase useCase = new ConfirmPurchaseUseCase(orders, inventory, metrics, CLOCK);

    private Order awaiting(java.time.Instant expiresAt) {
        return Order.pending("o1", "e1", "c1", 1, "c1#k", NOW.minusSeconds(100))
                .reserved(List.of("T-1"), expiresAt, NOW.minusSeconds(100))
                .pendingConfirmation(NOW.minusSeconds(99));
    }

    @Test
    void confirmsAHeldOrderBeforeItsDeadline() {
        Order awaiting = awaiting(NOW.plusSeconds(60));
        Order confirmed = awaiting.confirmed(NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(awaiting));
        when(inventory.confirmSale(awaiting)).thenReturn(Mono.just(confirmed));

        StepVerifier.create(useCase.execute("o1")).expectNext(confirmed).verifyComplete();
        verify(metrics).orderConfirmed();
    }

    @Test
    void confirmingTwiceIsIdempotent() {
        Order confirmed = awaiting(NOW.plusSeconds(60)).confirmed(NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(confirmed));

        StepVerifier.create(useCase.execute("o1")).expectNext(confirmed).verifyComplete();
        verifyNoInteractions(inventory, metrics);
    }

    @Test
    void refusesToConfirmAfterTheReservationDeadline() {
        when(orders.findById("o1")).thenReturn(Mono.just(awaiting(NOW)));
        StepVerifier.create(useCase.execute("o1")).expectError(ReservationExpiredException.class).verify();
        verify(inventory, never()).confirmSale(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredOrdersReportExpiry() {
        Order expired = awaiting(NOW.plusSeconds(60)).expired(NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(expired));
        StepVerifier.create(useCase.execute("o1")).expectError(ReservationExpiredException.class).verify();
    }

    @Test
    void ordersNotYetProcessedOrRejectedCannotBeConfirmed() {
        Order pending = Order.pending("o1", "e1", "c1", 1, "c1#k", NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(pending));
        StepVerifier.create(useCase.execute("o1")).expectError(InvalidStateTransitionException.class).verify();

        Order rejected = pending.rejected("SOLD_OUT", NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(rejected));
        StepVerifier.create(useCase.execute("o1")).expectError(InvalidStateTransitionException.class).verify();

        Order reserved = pending.reserved(List.of("T-1"), NOW.plusSeconds(60), NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(reserved));
        StepVerifier.create(useCase.execute("o1")).expectErrorMatches(e -> e instanceof InvalidStateTransitionException
                && reserved.status() == OrderStatus.RESERVED).verify();
    }

    @Test
    void unknownOrderFails() {
        when(orders.findById("o1")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute("o1")).expectError(OrderNotFoundException.class).verify();
    }
}
