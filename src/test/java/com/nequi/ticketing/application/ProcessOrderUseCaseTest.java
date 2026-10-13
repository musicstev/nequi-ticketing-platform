package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.CLOCK;
import static com.nequi.ticketing.application.TestFixtures.NOW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.InsufficientInventoryException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProcessOrderUseCaseTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final OrderRepository orders = mock(OrderRepository.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final OrderMetrics metrics = mock(OrderMetrics.class);
    private final ProcessOrderUseCase useCase = new ProcessOrderUseCase(orders, inventory, metrics, CLOCK, TTL);

    private Order pending() {
        return Order.pending("o1", "e1", "c1", 2, "c1#k", NOW);
    }

    @Test
    void reservesWithTenMinuteTtlThenMovesToPendingConfirmation() {
        Order pending = pending();
        Order reserved = pending.reserved(List.of("T-1", "T-2"), NOW.plus(TTL), NOW);
        Order awaiting = reserved.pendingConfirmation(NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(pending));
        when(inventory.reserve(pending, NOW.plus(TTL))).thenReturn(Mono.just(reserved));
        when(inventory.markPendingConfirmation(reserved)).thenReturn(Mono.just(awaiting));

        StepVerifier.create(useCase.execute("o1")).expectNext(awaiting).verifyComplete();
        verify(metrics).orderProcessed();
    }

    @Test
    void rejectsOrderWhenInventoryIsInsufficient() {
        Order pending = pending();
        when(orders.findById("o1")).thenReturn(Mono.just(pending));
        when(inventory.reserve(eq(pending), any())).thenReturn(Mono.error(new InsufficientInventoryException("e1", 2)));
        when(orders.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0, Order.class)));

        StepVerifier.create(useCase.execute("o1"))
                .expectNextMatches(o -> o.status() == OrderStatus.REJECTED && "SOLD_OUT".equals(o.rejectionReason()))
                .verifyComplete();
        verify(metrics).orderRejected();
        verify(inventory, never()).markPendingConfirmation(any());
    }

    @Test
    void resumesAnOrderThatWasReservedButNotAdvanced() {
        Order reserved = pending().reserved(List.of("T-1", "T-2"), NOW.plus(TTL), NOW);
        Order awaiting = reserved.pendingConfirmation(NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(reserved));
        when(inventory.markPendingConfirmation(reserved)).thenReturn(Mono.just(awaiting));

        StepVerifier.create(useCase.execute("o1")).expectNext(awaiting).verifyComplete();
        verify(inventory, never()).reserve(any(), any());
    }

    @Test
    void redeliveredMessageForAlreadyProcessedOrderIsANoOp() {
        Order awaiting = pending().reserved(List.of("T-1", "T-2"), NOW.plus(TTL), NOW).pendingConfirmation(NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(awaiting));

        StepVerifier.create(useCase.execute("o1")).expectNext(awaiting).verifyComplete();
        verifyNoInteractions(inventory, metrics);
    }

    @Test
    void terminalOrdersAreIgnored() {
        Order rejected = pending().rejected("SOLD_OUT", NOW);
        when(orders.findById("o1")).thenReturn(Mono.just(rejected));

        StepVerifier.create(useCase.execute("o1")).expectNext(rejected).verifyComplete();
        verifyNoInteractions(inventory);
    }

    @Test
    void unknownOrderFails() {
        when(orders.findById("o1")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute("o1")).expectError(OrderNotFoundException.class).verify();
    }

    @Test
    void transientConflictsPropagateSoTheMessageIsRetried() {
        when(orders.findById("o1")).thenReturn(Mono.just(pending()));
        when(inventory.reserve(any(), any())).thenReturn(Mono.error(new ConcurrentUpdateException("conflict")));

        StepVerifier.create(useCase.execute("o1")).expectError(ConcurrentUpdateException.class).verify();
        verify(orders, never()).update(any());
        verify(metrics, never()).orderRejected();
    }
}
