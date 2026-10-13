package com.nequi.ticketing.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.CreateEventCommand;
import com.nequi.ticketing.application.CreateEventUseCase;
import com.nequi.ticketing.application.GetAvailabilityUseCase;
import com.nequi.ticketing.application.GrantComplimentaryUseCase;
import com.nequi.ticketing.application.QueryEventsUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class EventControllerTest {

    private static final Instant DATE = Instant.parse("2031-05-01T20:00:00Z");

    private final CreateEventUseCase create = mock(CreateEventUseCase.class);
    private final QueryEventsUseCase query = mock(QueryEventsUseCase.class);
    private final GetAvailabilityUseCase availability = mock(GetAvailabilityUseCase.class);
    private final GrantComplimentaryUseCase complimentary = mock(GrantComplimentaryUseCase.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        EventController controller = new EventController(
                create, query, availability, complimentary, new AdminTokenGuard("s3cret"), Duration.ofMillis(50));
        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .validator(WebTestSupport.validator())
                .build();
    }

    private static Event event() {
        return new Event("evt-1", "Concert", DATE, "Arena", 100, Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void createsAnEventAndReturnsLocation() {
        when(create.execute(any())).thenReturn(Mono.just(event()));

        client.post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Concert","date":"2031-05-01T20:00:00Z","venue":"Arena","capacity":100}""")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/events/evt-1")
                .expectBody()
                .jsonPath("$.id").isEqualTo("evt-1")
                .jsonPath("$.capacity").isEqualTo(100)
                .jsonPath("$.date").isEqualTo("2031-05-01T20:00:00Z");

        ArgumentCaptor<CreateEventCommand> command = ArgumentCaptor.forClass(CreateEventCommand.class);
        verify(create).execute(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue())
                .isEqualTo(new CreateEventCommand("Concert", DATE, "Arena", 100));
    }

    @Test
    void invalidBodiesAreRejectedBeforeReachingTheUseCase() {
        client.post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":" ","date":"2031-05-01T20:00:00Z","venue":"Arena","capacity":0}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Validation failed")
                .jsonPath("$.errors.length()").isEqualTo(2);
        verifyNoInteractions(create);
    }

    @Test
    void malformedJsonIsABadRequest() {
        client.post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.title").isEqualTo("Malformed request");
    }

    @Test
    void returnsAnEventById() {
        when(query.get("evt-1")).thenReturn(Mono.just(event()));
        client.get().uri("/api/v1/events/evt-1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Concert");
    }

    @Test
    void unknownEventIsA404ProblemDocument() {
        when(query.get("nope")).thenReturn(Mono.error(new EventNotFoundException("nope")));
        client.get().uri("/api/v1/events/nope").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("Event not found: nope");
    }

    @Test
    void listsEvents() {
        when(query.list()).thenReturn(Flux.just(event(), event()));
        client.get().uri("/api/v1/events").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void exposesLiveAvailabilityIncludingHeldTickets() {
        when(availability.execute("evt-1")).thenReturn(Mono.just(new Availability("evt-1", 100, 70, 10, 5, 12, 3)));
        client.get().uri("/api/v1/events/evt-1/availability").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(70)
                .jsonPath("$.reserved").isEqualTo(10)
                .jsonPath("$.pendingConfirmation").isEqualTo(5)
                .jsonPath("$.sold").isEqualTo(12)
                .jsonPath("$.complimentary").isEqualTo(3)
                .jsonPath("$.unavailable").isEqualTo(30);
    }

    @Test
    void streamsAvailabilityAsServerSentEvents() {
        when(availability.stream("evt-1", Duration.ofMillis(50)))
                .thenReturn(Flux.just(Availability.initial("evt-1", 10), new Availability("evt-1", 10, 9, 1, 0, 0, 0)));

        List<AvailabilityResponse> events = client.get().uri("/api/v1/events/evt-1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(AvailabilityResponse.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(5));

        org.assertj.core.api.Assertions.assertThat(events).extracting(AvailabilityResponse::available).containsExactly(10, 9);
    }

    @Test
    void complimentaryGrantRequiresTheAdminToken() {
        client.post().uri("/api/v1/events/evt-1/complimentary")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":2}")
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/events/evt-1/complimentary")
                .header("X-Admin-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":2}")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(complimentary);
    }

    @Test
    void grantsComplimentaryTicketsWithAValidToken() {
        when(complimentary.execute("evt-1", 2)).thenReturn(Mono.just(List.of(
                new Ticket("evt-1", "T-000001", TicketStatus.COMPLIMENTARY, null, null, 1),
                new Ticket("evt-1", "T-000002", TicketStatus.COMPLIMENTARY, null, null, 1))));

        client.post().uri("/api/v1/events/evt-1/complimentary")
                .header("X-Admin-Token", "s3cret")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":2}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].status").isEqualTo("COMPLIMENTARY");
    }

    @Test
    void rejectsIdentifiersThatLookLikeInjection() {
        client.get().uri("/api/v1/events/{id}", "bad id with spaces").exchange()
                .expectStatus().isBadRequest();
        verifyNoInteractions(query);
    }
}
