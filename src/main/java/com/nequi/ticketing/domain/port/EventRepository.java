package com.nequi.ticketing.domain.port;

import com.nequi.ticketing.domain.model.Event;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EventRepository {

    /** Persists the event together with its full ticket inventory (all tickets AVAILABLE). */
    Mono<Event> create(Event event);

    Mono<Event> findById(String eventId);

    Flux<Event> findAll();
}
