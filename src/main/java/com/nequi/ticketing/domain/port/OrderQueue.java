package com.nequi.ticketing.domain.port;

import reactor.core.publisher.Mono;

public interface OrderQueue {

    /** Enqueues the order for asynchronous processing. */
    Mono<Void> publish(String orderId);
}
