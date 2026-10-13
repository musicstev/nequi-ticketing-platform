package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.ConfirmPurchaseUseCase;
import com.nequi.ticketing.application.GetOrderStatusUseCase;
import com.nequi.ticketing.application.OrderView;
import com.nequi.ticketing.application.PurchaseCommand;
import com.nequi.ticketing.application.RequestPurchaseUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final RequestPurchaseUseCase requestPurchase;
    private final GetOrderStatusUseCase getOrderStatus;
    private final ConfirmPurchaseUseCase confirmPurchase;

    public OrderController(
            RequestPurchaseUseCase requestPurchase,
            GetOrderStatusUseCase getOrderStatus,
            ConfirmPurchaseUseCase confirmPurchase) {
        this.requestPurchase = requestPurchase;
        this.getOrderStatus = getOrderStatus;
        this.confirmPurchase = confirmPurchase;
    }

    /** 202 Accepted: the order is stored and queued; poll {@code Location} for progress. Safe to retry with the same key. */
    @PostMapping
    public Mono<ResponseEntity<OrderAcceptedResponse>> purchase(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PurchaseRequest request) {
        return requestPurchase
                .execute(new PurchaseCommand(request.eventId(), request.customerId(), request.quantity(), idempotencyKey))
                .map(order -> ResponseEntity.accepted()
                        .location(URI.create("/api/v1/orders/" + order.id()))
                        .body(new OrderAcceptedResponse(order.id(), order.status().name())));
    }

    @GetMapping("/{id}")
    public Mono<OrderResponse> get(@PathVariable String id) {
        return Mono.fromSupplier(() -> Ids.require(id)).flatMap(getOrderStatus::execute).map(OrderController::toResponse);
    }

    @PostMapping("/{id}/confirm")
    public Mono<OrderResponse> confirm(@PathVariable String id) {
        return Mono.fromSupplier(() -> Ids.require(id))
                .flatMap(orderId -> confirmPurchase.execute(orderId).then(Mono.defer(() -> getOrderStatus.execute(orderId))))
                .map(OrderController::toResponse);
    }

    private static OrderResponse toResponse(OrderView view) {
        var order = view.order();
        var tickets = view.tickets().stream().map(TicketResponse::from).toList();
        String ticketStatus = tickets.isEmpty() ? null : tickets.getFirst().status();
        return new OrderResponse(
                order.id(),
                order.eventId(),
                order.status().name(),
                ticketStatus,
                order.quantity(),
                order.expiresAt(),
                order.rejectionReason(),
                tickets);
    }
}
