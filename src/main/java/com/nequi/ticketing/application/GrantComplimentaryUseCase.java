package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Clock;
import java.util.List;
import reactor.core.publisher.Mono;

/** Moves tickets AVAILABLE -> COMPLIMENTARY (final, not accountable as a sale). */
public class GrantComplimentaryUseCase {

    private final EventRepository events;
    private final TicketInventory inventory;
    private final Clock clock;
    private final int maxQuantity;

    public GrantComplimentaryUseCase(EventRepository events, TicketInventory inventory, Clock clock, int maxQuantity) {
        this.events = events;
        this.inventory = inventory;
        this.clock = clock;
        this.maxQuantity = maxQuantity;
    }

    public Mono<List<Ticket>> execute(String eventId, int quantity) {
        if (quantity < 1 || quantity > maxQuantity) {
            return Mono.error(new InvalidRequestException("quantity must be between 1 and " + maxQuantity));
        }
        return events.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .then(Mono.defer(() -> inventory.grantComplimentary(eventId, quantity, clock.instant())));
    }
}
