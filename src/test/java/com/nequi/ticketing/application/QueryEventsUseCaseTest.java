package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.event;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.port.EventRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QueryEventsUseCaseTest {

    private final EventRepository events = mock(EventRepository.class);
    private final QueryEventsUseCase useCase = new QueryEventsUseCase(events);

    @Test
    void returnsExistingEvent() {
        when(events.findById("e1")).thenReturn(Mono.just(event("e1", 10)));
        StepVerifier.create(useCase.get("e1")).expectNextMatches(e -> e.id().equals("e1")).verifyComplete();
    }

    @Test
    void failsWithNotFoundForUnknownEvent() {
        when(events.findById("nope")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.get("nope")).expectError(EventNotFoundException.class).verify();
    }

    @Test
    void listsAllEvents() {
        when(events.findAll()).thenReturn(Flux.just(event("a", 1), event("b", 2)));
        StepVerifier.create(useCase.list()).expectNextCount(2).verifyComplete();
    }
}
