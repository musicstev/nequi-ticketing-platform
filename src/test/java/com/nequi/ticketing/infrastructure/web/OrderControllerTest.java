package com.nequi.ticketing.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.ConfirmPurchaseUseCase;
import com.nequi.ticketing.application.GetOrderStatusUseCase;
import com.nequi.ticketing.application.OrderView;
import com.nequi.ticketing.application.PurchaseCommand;
import com.nequi.ticketing.application.RequestPurchaseUseCase;
import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.exception.ReservationExpiredException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class OrderControllerTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final RequestPurchaseUseCase purchase = mock(RequestPurchaseUseCase.class);
    private final GetOrderStatusUseCase status = mock(GetOrderStatusUseCase.class);
    private final ConfirmPurchaseUseCase confirm = mock(ConfirmPurchaseUseCase.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new OrderController(purchase, status, confirm))
                .controllerAdvice(new ApiExceptionHandler())
                .validator(WebTestSupport.validator())
                .build();
    }

    private static Order pending() {
        return Order.pending("ord-1", "evt-1", "cust-1", 2, "cust-1#key-12345", NOW);
    }

    @Test
    void acceptsAPurchaseWith202AndLocation() {
        when(purchase.execute(any())).thenReturn(Mono.just(pending()));

        client.post().uri("/api/v1/orders")
                .header("Idempotency-Key", "key-12345")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"eventId":"evt-1","customerId":"cust-1","quantity":2}""")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/api/v1/orders/ord-1")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord-1")
                .jsonPath("$.status").isEqualTo("PENDING");

        ArgumentCaptor<PurchaseCommand> command = ArgumentCaptor.forClass(PurchaseCommand.class);
        verify(purchase).execute(command.capture());
        assertThat(command.getValue()).isEqualTo(new PurchaseCommand("evt-1", "cust-1", 2, "key-12345"));
    }

    @Test
    void aMissingIdempotencyKeyIsRejectedByTheUseCaseWith400() {
        when(purchase.execute(any())).thenReturn(Mono.error(new InvalidRequestException("Idempotency-Key must match")));

        client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"eventId":"evt-1","customerId":"cust-1","quantity":2}""")
                .exchange()
                .expectStatus().isBadRequest();

        ArgumentCaptor<PurchaseCommand> command = ArgumentCaptor.forClass(PurchaseCommand.class);
        verify(purchase).execute(command.capture());
        assertThat(command.getValue().idempotencyKey()).isNull();
    }

    @Test
    void invalidPurchaseBodiesNeverReachTheUseCase() {
        client.post().uri("/api/v1/orders")
                .header("Idempotency-Key", "key-12345")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"eventId":"","customerId":"cust-1","quantity":-3}""")
                .exchange()
                .expectStatus().isBadRequest();
        verifyNoInteractions(purchase);
    }

    @Test
    void reportsOrderStateTogetherWithTicketStates() {
        Order held = pending().reserved(List.of("T-000001", "T-000002"), NOW.plusSeconds(600), NOW).pendingConfirmation(NOW);
        List<Ticket> tickets = List.of(
                new Ticket("evt-1", "T-000001", TicketStatus.PENDING_CONFIRMATION, "ord-1", NOW.plusSeconds(600), 2),
                new Ticket("evt-1", "T-000002", TicketStatus.PENDING_CONFIRMATION, "ord-1", NOW.plusSeconds(600), 2));
        when(status.execute("ord-1")).thenReturn(Mono.just(new OrderView(held, tickets)));

        client.get().uri("/api/v1/orders/ord-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION")
                .jsonPath("$.ticketStatus").isEqualTo("PENDING_CONFIRMATION")
                .jsonPath("$.expiresAt").isEqualTo("2030-01-01T10:10:00Z")
                .jsonPath("$.tickets.length()").isEqualTo(2)
                .jsonPath("$.tickets[0].ticketId").isEqualTo("T-000001")
                .jsonPath("$.tickets[0].status").isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void queuedOrdersHaveNoTicketStatusYet() {
        when(status.execute("ord-1")).thenReturn(Mono.just(new OrderView(pending(), List.of())));
        client.get().uri("/api/v1/orders/ord-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.ticketStatus").doesNotExist()
                .jsonPath("$.tickets.length()").isEqualTo(0);
    }

    @Test
    void rejectedOrdersExposeTheReason() {
        when(status.execute("ord-1")).thenReturn(Mono.just(new OrderView(pending().rejected("SOLD_OUT", NOW), List.of())));
        client.get().uri("/api/v1/orders/ord-1").exchange()
                .expectBody().jsonPath("$.rejectionReason").isEqualTo("SOLD_OUT");
    }

    @Test
    void unknownOrderIs404() {
        when(status.execute("nope")).thenReturn(Mono.error(new OrderNotFoundException("nope")));
        client.get().uri("/api/v1/orders/nope").exchange().expectStatus().isNotFound();
    }

    @Test
    void confirmingReturnsTheSoldTickets() {
        Order held = pending().reserved(List.of("T-000001", "T-000002"), NOW.plusSeconds(600), NOW).pendingConfirmation(NOW);
        Order confirmed = held.confirmed(NOW);
        when(confirm.execute("ord-1")).thenReturn(Mono.just(confirmed));
        when(status.execute("ord-1")).thenReturn(Mono.just(new OrderView(confirmed, List.of(
                new Ticket("evt-1", "T-000001", TicketStatus.SOLD, "ord-1", null, 3),
                new Ticket("evt-1", "T-000002", TicketStatus.SOLD, "ord-1", null, 3)))));

        client.post().uri("/api/v1/orders/ord-1/confirm").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CONFIRMED")
                .jsonPath("$.ticketStatus").isEqualTo("SOLD");
    }

    @Test
    void confirmingAfterTheDeadlineIs410() {
        when(confirm.execute("ord-1")).thenReturn(Mono.error(new ReservationExpiredException("ord-1")));
        client.post().uri("/api/v1/orders/ord-1/confirm").exchange().expectStatus().isEqualTo(410);
    }

    @Test
    void malformedOrderIdsAreRejected() {
        client.get().uri("/api/v1/orders/{id}", "x".repeat(65)).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(status);
    }
}
