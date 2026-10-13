package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import reactor.core.publisher.Mono;

public class GetOrderStatusUseCase {

    private final OrderRepository orders;
    private final TicketInventory inventory;

    public GetOrderStatusUseCase(OrderRepository orders, TicketInventory inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    public Mono<OrderView> execute(String orderId) {
        return orders.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMap(order -> inventory.ticketsOf(order).map(tickets -> new OrderView(order, tickets)));
    }
}
