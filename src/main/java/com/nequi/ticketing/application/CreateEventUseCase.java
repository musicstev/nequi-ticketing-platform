package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import reactor.core.publisher.Mono;

public class CreateEventUseCase {

    private final EventRepository events;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateEventUseCase(EventRepository events, IdGenerator ids, Clock clock) {
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Event> execute(CreateEventCommand command) {
        Instant now = clock.instant();
        Event event;
        try {
            event = new Event(ids.next(), command.name(), command.date(), command.venue(), command.capacity(), now);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InvalidRequestException(e.getMessage()));
        }
        if (!event.date().isAfter(now)) {
            return Mono.error(new InvalidRequestException("Event date must be in the future"));
        }
        return events.create(event);
    }
}
