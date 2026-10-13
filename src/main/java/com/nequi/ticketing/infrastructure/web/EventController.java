package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.CreateEventCommand;
import com.nequi.ticketing.application.CreateEventUseCase;
import com.nequi.ticketing.application.GetAvailabilityUseCase;
import com.nequi.ticketing.application.GrantComplimentaryUseCase;
import com.nequi.ticketing.application.QueryEventsUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final CreateEventUseCase createEvent;
    private final QueryEventsUseCase queryEvents;
    private final GetAvailabilityUseCase getAvailability;
    private final GrantComplimentaryUseCase grantComplimentary;
    private final AdminTokenGuard adminGuard;
    private final Duration streamInterval;

    public EventController(
            CreateEventUseCase createEvent,
            QueryEventsUseCase queryEvents,
            GetAvailabilityUseCase getAvailability,
            GrantComplimentaryUseCase grantComplimentary,
            AdminTokenGuard adminGuard,
            Duration streamInterval) {
        this.createEvent = createEvent;
        this.queryEvents = queryEvents;
        this.getAvailability = getAvailability;
        this.grantComplimentary = grantComplimentary;
        this.adminGuard = adminGuard;
        this.streamInterval = streamInterval;
    }

    @PostMapping
    public Mono<ResponseEntity<EventResponse>> create(@Valid @RequestBody CreateEventRequest request) {
        return createEvent
                .execute(new CreateEventCommand(request.name(), request.date(), request.venue(), request.capacity()))
                .map(EventResponse::from)
                .map(body -> ResponseEntity.created(URI.create("/api/v1/events/" + body.id())).body(body));
    }

    @GetMapping
    public Flux<EventResponse> list() {
        return queryEvents.list().map(EventResponse::from);
    }

    @GetMapping("/{id}")
    public Mono<EventResponse> get(@PathVariable String id) {
        return Mono.fromSupplier(() -> Ids.require(id)).flatMap(queryEvents::get).map(EventResponse::from);
    }

    @GetMapping("/{id}/availability")
    public Mono<AvailabilityResponse> availability(@PathVariable String id) {
        return Mono.fromSupplier(() -> Ids.require(id)).flatMap(getAvailability::execute).map(AvailabilityResponse::from);
    }

    /** Server-sent events: the current availability, then a new event whenever it changes. */
    @GetMapping(path = "/{id}/availability/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AvailabilityResponse> availabilityStream(@PathVariable String id) {
        return Mono.fromSupplier(() -> Ids.require(id))
                .flatMapMany(eventId -> getAvailability.stream(eventId, streamInterval))
                .map(AvailabilityResponse::from);
    }

    @PostMapping("/{id}/complimentary")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<List<TicketResponse>> grantComplimentary(
            @PathVariable String id,
            @RequestHeader(name = "X-Admin-Token", required = false) String adminToken,
            @Valid @RequestBody ComplimentaryRequest request) {
        return Mono.fromRunnable(() -> adminGuard.require(adminToken))
                .then(Mono.fromSupplier(() -> Ids.require(id)))
                .flatMap(eventId -> grantComplimentary.execute(eventId, request.quantity()))
                .map(tickets -> tickets.stream().map(TicketResponse::from).toList());
    }
}
