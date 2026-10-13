package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Ticket;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request/response contracts of the HTTP API. Responses never expose internal keys such as idempotency keys. */
final class Dtos {

    private Dtos() {}
}

record CreateEventRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull Instant date,
        @NotBlank @Size(max = 120) String venue,
        @Min(1) @Max(Event.MAX_CAPACITY) int capacity) {}

record EventResponse(String id, String name, Instant date, String venue, int capacity, Instant createdAt) {

    static EventResponse from(Event e) {
        return new EventResponse(e.id(), e.name(), e.date(), e.venue(), e.capacity(), e.createdAt());
    }
}

record AvailabilityResponse(
        String eventId,
        int capacity,
        int available,
        int reserved,
        int pendingConfirmation,
        int sold,
        int complimentary,
        int unavailable) {

    static AvailabilityResponse from(Availability a) {
        return new AvailabilityResponse(a.eventId(), a.capacity(), a.available(), a.reserved(),
                a.pendingConfirmation(), a.sold(), a.complimentary(), a.unavailable());
    }
}

record PurchaseRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String customerId,
        @Min(1) @Max(100) int quantity) {}

record OrderAcceptedResponse(String orderId, String status) {}

record TicketResponse(String ticketId, String status) {

    static TicketResponse from(Ticket t) {
        return new TicketResponse(t.ticketId(), t.status().name());
    }
}

record OrderResponse(
        String orderId,
        String eventId,
        String status,
        String ticketStatus,
        int quantity,
        Instant expiresAt,
        String rejectionReason,
        List<TicketResponse> tickets) {}

record ComplimentaryRequest(@Min(1) @Max(100) int quantity) {}
