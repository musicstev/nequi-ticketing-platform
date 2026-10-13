package com.nequi.ticketing.infrastructure.metrics;

import com.nequi.ticketing.domain.port.OrderMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class MicrometerOrderMetrics implements OrderMetrics {

    private final Counter processed;
    private final Counter rejected;
    private final Counter failed;
    private final Counter confirmed;
    private final Counter expired;

    public MicrometerOrderMetrics(MeterRegistry registry) {
        this.processed = Counter.builder("ticketing.orders.processed")
                .description("Orders reserved and moved to pending confirmation")
                .register(registry);
        this.rejected = Counter.builder("ticketing.orders.rejected")
                .description("Orders rejected because the event sold out")
                .register(registry);
        this.failed = Counter.builder("ticketing.orders.failed")
                .description("Order messages that could not be processed (left for redelivery / DLQ)")
                .register(registry);
        this.confirmed = Counter.builder("ticketing.orders.confirmed")
                .description("Orders confirmed (tickets SOLD)")
                .register(registry);
        this.expired = Counter.builder("ticketing.reservations.expired")
                .description("Reservations released after the 10 minute deadline")
                .register(registry);
    }

    @Override
    public void orderProcessed() {
        processed.increment();
    }

    @Override
    public void orderRejected() {
        rejected.increment();
    }

    @Override
    public void orderFailed() {
        failed.increment();
    }

    @Override
    public void orderConfirmed() {
        confirmed.increment();
    }

    @Override
    public void reservationsExpired(int count) {
        expired.increment(count);
    }
}
