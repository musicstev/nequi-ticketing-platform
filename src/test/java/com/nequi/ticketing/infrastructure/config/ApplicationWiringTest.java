package com.nequi.ticketing.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.domain.port.OrderQueue;
import com.nequi.ticketing.infrastructure.messaging.sqs.SqsOrderListener;
import com.nequi.ticketing.infrastructure.scheduler.ExpiryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Boots the full context (no AWS calls: listener, scheduler and table bootstrap are switched off). */
@SpringBootTest(properties = {
    "app.sqs.listener-enabled=false",
    "app.reservation.scheduler-enabled=false",
    "app.dynamodb.bootstrap-tables=false",
    "app.security.admin-token=test-token"
})
class ApplicationWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextWiresPortsToAdaptersAndSkipsDisabledLifecycles() {
        assertThat(context.getBean(OrderQueue.class)).isNotNull();
        assertThat(context.getBeansOfType(SqsOrderListener.class)).isEmpty();
        assertThat(context.getBeansOfType(ExpiryScheduler.class)).isEmpty();
    }

    @Test
    void actuatorExposesOnlyHealthAndPrometheusWithoutDetails() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP").jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("ticketing_orders_processed_total"));
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }

    @Test
    void apiAppliesCorrelationHeaderAndValidation() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

        client.post().uri("/api/v1/events")
                .header("Content-Type", "application/json")
                .bodyValue("{\"name\":\"\",\"venue\":\"\",\"capacity\":0}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.title").isEqualTo("Validation failed");
    }
}
