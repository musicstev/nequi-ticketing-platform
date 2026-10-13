package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.port.EventRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class QueryEventsUseCase {

    private final EventRepository events;

    public QueryEventsUseCase(EventRepository events) {
        this.events = events;
    }

    public Mono<Event> get(String eventId) {
        return events.findById(eventId).switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }

    public Flux<Event> list() {
        return events.findAll();
    }
}
