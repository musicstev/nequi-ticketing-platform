package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.CLOCK;
import static com.nequi.ticketing.application.TestFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReleaseExpiredReservationsUseCaseTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final OrderMetrics metrics = mock(OrderMetrics.class);
    private final ReleaseExpiredReservationsUseCase useCase =
            new ReleaseExpiredReservationsUseCase(orders, inventory, metrics, CLOCK, 50);

    private Order held(String id) {
        return Order.pending(id, "e1", "c1", 1, "c1#" + id, NOW.minusSeconds(700))
                .reserved(List.of("T-" + id), NOW.minusSeconds(100), NOW.minusSeconds(700));
    }

    @Test
    void releasesEveryExpiredOrderAndReportsTheCount() {
        Order a = held("a");
        Order b = held("b");
        when(orders.findExpired(NOW, 50)).thenReturn(Flux.just(a, b));
        when(inventory.release(a)).thenReturn(Mono.just(a.expired(NOW)));
        when(inventory.release(b)).thenReturn(Mono.just(b.expired(NOW)));

        StepVerifier.create(useCase.execute()).expectNext(2).verifyComplete();
        verify(metrics).reservationsExpired(2);
    }

    @Test
    void skipsOrdersThatChangedConcurrentlyForExampleConfirmedJustInTime() {
        Order a = held("a");
        Order b = held("b");
        when(orders.findExpired(NOW, 50)).thenReturn(Flux.just(a, b));
        when(inventory.release(a)).thenReturn(Mono.error(new ConcurrentUpdateException("confirmed meanwhile")));
        when(inventory.release(b)).thenReturn(Mono.just(b.expired(NOW)));

        StepVerifier.create(useCase.execute()).expectNext(1).verifyComplete();
        verify(metrics).reservationsExpired(1);
    }

    @Test
    void doesNothingWhenNothingExpired() {
        when(orders.findExpired(NOW, 50)).thenReturn(Flux.empty());
        StepVerifier.create(useCase.execute()).expectNext(0).verifyComplete();
        verify(metrics, never()).reservationsExpired(org.mockito.ArgumentMatchers.anyInt());
    }
}
