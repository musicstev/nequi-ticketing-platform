package com.nequi.ticketing.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerOrderMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerOrderMetrics metrics = new MicrometerOrderMetrics(registry);

    @Test
    void countsOrderOutcomes() {
        metrics.orderProcessed();
        metrics.orderProcessed();
        metrics.orderRejected();
        metrics.orderFailed();
        metrics.orderConfirmed();
        metrics.reservationsExpired(3);
        metrics.reservationsExpired(2);

        assertThat(registry.counter("ticketing.orders.processed").count()).isEqualTo(2);
        assertThat(registry.counter("ticketing.orders.rejected").count()).isEqualTo(1);
        assertThat(registry.counter("ticketing.orders.failed").count()).isEqualTo(1);
        assertThat(registry.counter("ticketing.orders.confirmed").count()).isEqualTo(1);
        assertThat(registry.counter("ticketing.reservations.expired").count()).isEqualTo(5);
    }
}
