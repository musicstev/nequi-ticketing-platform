package com.nequi.ticketing.infrastructure.config;

import com.nequi.ticketing.application.ConfirmPurchaseUseCase;
import com.nequi.ticketing.application.CreateEventUseCase;
import com.nequi.ticketing.application.GetAvailabilityUseCase;
import com.nequi.ticketing.application.GetOrderStatusUseCase;
import com.nequi.ticketing.application.GrantComplimentaryUseCase;
import com.nequi.ticketing.application.ProcessOrderUseCase;
import com.nequi.ticketing.application.QueryEventsUseCase;
import com.nequi.ticketing.application.ReleaseExpiredReservationsUseCase;
import com.nequi.ticketing.application.RequestPurchaseUseCase;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderQueue;
import com.nequi.ticketing.infrastructure.messaging.sqs.SqsListenerSettings;
import com.nequi.ticketing.infrastructure.messaging.sqs.SqsOrderListener;
import com.nequi.ticketing.infrastructure.messaging.sqs.SqsOrderQueue;
import com.nequi.ticketing.infrastructure.messaging.sqs.SqsQueueResolver;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTableBootstrap;
import com.nequi.ticketing.infrastructure.scheduler.ExpiryScheduler;
import com.nequi.ticketing.infrastructure.web.AdminTokenGuard;
import com.nequi.ticketing.infrastructure.web.CorrelationIdWebFilter;
import com.nequi.ticketing.infrastructure.web.EventController;
import com.nequi.ticketing.infrastructure.web.OrderController;
import com.nequi.ticketing.infrastructure.web.RateLimitWebFilter;
import com.nequi.ticketing.infrastructure.web.TokenBucketRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Adapters with a lifecycle (queue, listener, scheduler) and the web layer. */
@Configuration(proxyBeanMethods = false)
class RuntimeComponentsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeComponentsConfig.class);
    private static final int MAX_RATE_LIMITED_CLIENTS_FALLBACK = 10_000;

    /**
     * Local development only: creates the tables before anything uses them. Blocking here is intentional,
     * it runs once on the startup thread and not inside any reactive pipeline.
     */
    @Bean
    @ConditionalOnProperty(name = "app.dynamodb.bootstrap-tables", havingValue = "true")
    InitializingBean dynamoTableInitializer(DynamoTableBootstrap bootstrap) {
        return () -> {
            bootstrap.ensureTables()
                    .retryWhen(Retry.backoff(15, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5)))
                    .block(Duration.ofMinutes(2));
            LOG.info("DynamoDB tables are ready");
        };
    }

    @Bean
    SqsQueueResolver sqsQueueResolver(SqsAsyncClient client, AppProperties props) {
        return new SqsQueueResolver(client, props.sqs().queueName());
    }

    @Bean
    OrderQueue orderQueue(SqsAsyncClient client, SqsQueueResolver resolver, AppProperties props) {
        return new SqsOrderQueue(client, resolver, props.sqs().publishRetries(), Duration.ofMillis(100));
    }

    @Bean
    @ConditionalOnProperty(name = "app.sqs.listener-enabled", havingValue = "true", matchIfMissing = true)
    SqsOrderListener sqsOrderListener(
            SqsAsyncClient client,
            SqsQueueResolver resolver,
            ProcessOrderUseCase processOrder,
            OrderMetrics metrics,
            AppProperties props) {
        var sqs = props.sqs();
        return new SqsOrderListener(client, resolver, processOrder, metrics, new SqsListenerSettings(
                sqs.waitTimeSeconds(),
                sqs.maxMessages(),
                sqs.concurrency(),
                sqs.visibilityTimeoutSeconds(),
                sqs.processingRetries(),
                Duration.ofMillis(200),
                Duration.ofSeconds(1)));
    }

    @Bean
    @ConditionalOnProperty(name = "app.reservation.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    ExpiryScheduler expiryScheduler(ReleaseExpiredReservationsUseCase releaseExpired, AppProperties props) {
        return new ExpiryScheduler(releaseExpired, props.reservation().releaseInterval());
    }

    @Bean
    AdminTokenGuard adminTokenGuard(AppProperties props) {
        return new AdminTokenGuard(props.security().adminToken());
    }

    @Bean
    EventController eventController(
            CreateEventUseCase create,
            QueryEventsUseCase query,
            GetAvailabilityUseCase availability,
            GrantComplimentaryUseCase complimentary,
            AdminTokenGuard guard,
            AppProperties props) {
        return new EventController(create, query, availability, complimentary, guard, props.availabilityStreamInterval());
    }

    @Bean
    OrderController orderController(
            RequestPurchaseUseCase purchase, GetOrderStatusUseCase status, ConfirmPurchaseUseCase confirm) {
        return new OrderController(purchase, status, confirm);
    }

    @Bean
    CorrelationIdWebFilter correlationIdWebFilter() {
        return new CorrelationIdWebFilter();
    }

    @Bean
    RateLimitWebFilter rateLimitWebFilter(Clock clock, AppProperties props) {
        var rl = props.rateLimit();
        int maxClients = rl.maxClients() > 0 ? rl.maxClients() : MAX_RATE_LIMITED_CLIENTS_FALLBACK;
        return new RateLimitWebFilter(
                new TokenBucketRateLimiter(clock, rl.capacity(), rl.refillPerSecond(), maxClients), rl.trustForwardedFor());
    }
}
