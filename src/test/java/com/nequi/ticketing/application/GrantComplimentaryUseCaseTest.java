package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.CLOCK;
import static com.nequi.ticketing.application.TestFixtures.NOW;
import static com.nequi.ticketing.application.TestFixtures.event;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GrantComplimentaryUseCaseTest {

    private final EventRepository events = mock(EventRepository.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final GrantComplimentaryUseCase useCase = new GrantComplimentaryUseCase(events, inventory, CLOCK, 40);

    @Test
    void grantsComplimentaryTickets() {
        List<Ticket> granted = List.of(new Ticket("e1", "T-1", TicketStatus.COMPLIMENTARY, null, null, 1));
        when(events.findById("e1")).thenReturn(Mono.just(event("e1", 10)));
        when(inventory.grantComplimentary("e1", 1, NOW)).thenReturn(Mono.just(granted));

        StepVerifier.create(useCase.execute("e1", 1)).expectNext(granted).verifyComplete();
    }

    @Test
    void validatesQuantity() {
        StepVerifier.create(useCase.execute("e1", 0)).expectError(InvalidRequestException.class).verify();
        StepVerifier.create(useCase.execute("e1", 41)).expectError(InvalidRequestException.class).verify();
        verifyNoInteractions(events, inventory);
    }

    @Test
    void failsForUnknownEvent() {
        when(events.findById("e1")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute("e1", 1)).expectError(EventNotFoundException.class).verify();
    }
}
