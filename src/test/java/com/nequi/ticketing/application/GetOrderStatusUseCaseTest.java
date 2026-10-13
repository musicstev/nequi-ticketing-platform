package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetOrderStatusUseCaseTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final GetOrderStatusUseCase useCase = new GetOrderStatusUseCase(orders, inventory);

    @Test
    void returnsOrderTogetherWithItsTicketStates() {
        Order order = Order.pending("o1", "e1", "c1", 1, "c1#k", NOW).reserved(List.of("T-1"), NOW.plusSeconds(60), NOW);
        Ticket ticket = new Ticket("e1", "T-1", TicketStatus.RESERVED, "o1", NOW.plusSeconds(60), 1);
        when(orders.findById("o1")).thenReturn(Mono.just(order));
        when(inventory.ticketsOf(order)).thenReturn(Mono.just(List.of(ticket)));

        StepVerifier.create(useCase.execute("o1"))
                .expectNextMatches(view -> view.order().equals(order) && view.tickets().equals(List.of(ticket)))
                .verifyComplete();
    }

    @Test
    void failsForUnknownOrder() {
        when(orders.findById("nope")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute("nope")).expectError(OrderNotFoundException.class).verify();
    }
}
