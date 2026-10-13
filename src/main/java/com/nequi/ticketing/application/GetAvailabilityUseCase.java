package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class GetAvailabilityUseCase {

    private final EventRepository events;
    private final TicketInventory inventory;

    public GetAvailabilityUseCase(EventRepository events, TicketInventory inventory) {
        this.events = events;
        this.inventory = inventory;
    }

    /** Live counters: sold, held (reserved / pending confirmation) and complimentary are all unavailable. */
    public Mono<Availability> execute(String eventId) {
        return events.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .then(Mono.defer(() -> inventory.availability(eventId)));
    }

    /** Server-sent stream: emits the current value and then only when it changes. */
    public Flux<Availability> stream(String eventId, Duration interval) {
        return Flux.interval(Duration.ZERO, interval)
                .onBackpressureDrop()
                .concatMap(tick -> execute(eventId))
                .distinctUntilChanged();
    }
}
