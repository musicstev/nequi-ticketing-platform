package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.event;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetAvailabilityUseCaseTest {

    private final EventRepository events = mock(EventRepository.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final GetAvailabilityUseCase useCase = new GetAvailabilityUseCase(events, inventory);

    @Test
    void reportsSoldAndHeldTicketsAsUnavailable() {
        when(events.findById("e1")).thenReturn(Mono.just(event("e1", 100)));
        when(inventory.availability("e1")).thenReturn(Mono.just(new Availability("e1", 100, 70, 10, 5, 15, 0)));

        StepVerifier.create(useCase.execute("e1"))
                .expectNextMatches(a -> a.available() == 70 && a.held() == 15 && a.unavailable() == 30)
                .verifyComplete();
    }

    @Test
    void failsForUnknownEvent() {
        when(events.findById("nope")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute("nope")).expectError(EventNotFoundException.class).verify();
    }

    @Test
    void streamEmitsOnlyWhenAvailabilityChanges() {
        when(events.findById("e1")).thenReturn(Mono.just(event("e1", 10)));
        Availability first = new Availability("e1", 10, 10, 0, 0, 0, 0);
        Availability second = new Availability("e1", 10, 9, 1, 0, 0, 0);
        when(inventory.availability("e1")).thenReturn(Mono.just(first), Mono.just(first), Mono.just(second));

        StepVerifier.withVirtualTime(() -> useCase.stream("e1", Duration.ofSeconds(2)).take(2))
                .expectNext(first)
                .thenAwait(Duration.ofSeconds(4))
                .expectNext(second)
                .verifyComplete();
    }
}
