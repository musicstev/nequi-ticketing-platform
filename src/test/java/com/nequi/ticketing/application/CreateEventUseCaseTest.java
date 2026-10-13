package com.nequi.ticketing.application;

import static com.nequi.ticketing.application.TestFixtures.CLOCK;
import static com.nequi.ticketing.application.TestFixtures.NOW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdGenerator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CreateEventUseCaseTest {

    private final EventRepository events = mock(EventRepository.class);
    private final IdGenerator ids = () -> "evt-1";
    private final CreateEventUseCase useCase = new CreateEventUseCase(events, ids, CLOCK);

    @Test
    void persistsEventWithGeneratedIdAndCurrentTimestamp() {
        when(events.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0, Event.class)));

        StepVerifier.create(useCase.execute(new CreateEventCommand("Concert", NOW.plusSeconds(60), "Arena", 100)))
                .expectNextMatches(e -> e.id().equals("evt-1") && e.capacity() == 100 && e.createdAt().equals(NOW))
                .verifyComplete();
    }

    @Test
    void rejectsPastDates() {
        StepVerifier.create(useCase.execute(new CreateEventCommand("Concert", NOW.minusSeconds(1), "Arena", 100)))
                .expectError(InvalidRequestException.class)
                .verify();
        verifyNoInteractions(events);
    }

    @Test
    void translatesDomainValidationIntoInvalidRequest() {
        StepVerifier.create(useCase.execute(new CreateEventCommand("Concert", NOW.plusSeconds(60), "Arena", 0)))
                .expectError(InvalidRequestException.class)
                .verify();
        verifyNoInteractions(events);
    }
}
