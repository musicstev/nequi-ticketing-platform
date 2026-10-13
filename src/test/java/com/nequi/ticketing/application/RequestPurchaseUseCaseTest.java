package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.CLOCK;
import static com.nequi.ticketing.application.TestFixtures.NOW;
import static com.nequi.ticketing.application.TestFixtures.event;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.IdempotencyConflictException;
import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.exception.QueueUnavailableException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.OrderQueue;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.OrderRepository.CreationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestPurchaseUseCaseTest {

    private final EventRepository events = mock(EventRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderQueue queue = mock(OrderQueue.class);
    private final RequestPurchaseUseCase useCase =
            new RequestPurchaseUseCase(events, orders, queue, () -> "ord-1", CLOCK, 10);

    @BeforeEach
    void stubEvent() {
        when(events.findById("evt-1")).thenReturn(Mono.just(event("evt-1", 100)));
    }

    private static PurchaseCommand command(int quantity) {
        return new PurchaseCommand("evt-1", "cust-1", quantity, "key-12345");
    }

    @Test
    void storesPendingOrderEnqueuesItAndReturnsImmediately() {
        when(orders.createIfAbsent(any())).thenAnswer(inv -> Mono.just(new CreationResult(inv.getArgument(0), true)));
        when(queue.publish("ord-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command(2)))
                .expectNextMatches(o -> o.id().equals("ord-1")
                        && o.status() == OrderStatus.PENDING
                        && o.quantity() == 2
                        && o.idempotencyKey().equals("cust-1#key-12345")
                        && o.createdAt().equals(NOW))
                .verifyComplete();
        verify(queue).publish("ord-1");
    }

    @Test
    void duplicateRequestReturnsSameOrderAndReEnqueuesOnlyWhilePending() {
        Order existing = Order.pending("ord-0", "evt-1", "cust-1", 2, "cust-1#key-12345", NOW);
        when(orders.createIfAbsent(any())).thenReturn(Mono.just(new CreationResult(existing, false)));
        when(queue.publish("ord-0")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command(2))).expectNext(existing).verifyComplete();
        verify(queue).publish("ord-0");
    }

    @Test
    void duplicateOfAlreadyProcessedOrderIsNotEnqueuedAgain() {
        Order processed = Order.pending("ord-0", "evt-1", "cust-1", 2, "cust-1#key-12345", NOW)
                .reserved(List.of("T-1", "T-2"), NOW.plusSeconds(600), NOW);
        when(orders.createIfAbsent(any())).thenReturn(Mono.just(new CreationResult(processed, false)));

        StepVerifier.create(useCase.execute(command(2))).expectNext(processed).verifyComplete();
        verify(queue, never()).publish(any());
    }

    @Test
    void reusingKeyForDifferentRequestIsAConflict() {
        Order existing = Order.pending("ord-0", "evt-1", "cust-1", 5, "cust-1#key-12345", NOW);
        when(orders.createIfAbsent(any())).thenReturn(Mono.just(new CreationResult(existing, false)));

        StepVerifier.create(useCase.execute(command(2))).expectError(IdempotencyConflictException.class).verify();
        verify(queue, never()).publish(any());
    }

    @Test
    void unknownEventFailsBeforeStoringAnything() {
        when(events.findById("evt-1")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command(1))).expectError(EventNotFoundException.class).verify();
        verifyNoInteractions(orders, queue);
    }

    @Test
    void queueFailurePropagatesAndKeepsOrderPendingForAKeyedRetry() {
        when(orders.createIfAbsent(any())).thenAnswer(inv -> Mono.just(new CreationResult(inv.getArgument(0), true)));
        when(queue.publish("ord-1")).thenReturn(Mono.error(new QueueUnavailableException("down", null)));

        StepVerifier.create(useCase.execute(command(1))).expectError(QueueUnavailableException.class).verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11})
    void rejectsQuantityOutsideAllowedRange(int quantity) {
        StepVerifier.create(useCase.execute(command(quantity))).expectError(InvalidRequestException.class).verify();
        verifyNoInteractions(orders, queue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "has space", "bad/slash", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsMalformedIdempotencyKeys(String key) {
        StepVerifier.create(useCase.execute(new PurchaseCommand("evt-1", "cust-1", 1, key)))
                .expectError(InvalidRequestException.class)
                .verify();
        verifyNoInteractions(orders, queue);
    }

    @Test
    void rejectsMissingKeyAndMissingCustomerOrEvent() {
        for (PurchaseCommand bad : List.of(
                new PurchaseCommand("evt-1", "cust-1", 1, null),
                new PurchaseCommand("evt-1", " ", 1, "key-12345"),
                new PurchaseCommand("evt-1", "c".repeat(65), 1, "key-12345"),
                new PurchaseCommand(null, "cust-1", 1, "key-12345"))) {
            StepVerifier.create(useCase.execute(bad)).expectError(InvalidRequestException.class).verify();
        }
    }
}
