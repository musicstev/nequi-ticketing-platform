package com.nequi.ticketing.infrastructure.messaging.sqs;

import com.nequi.ticketing.domain.exception.QueueUnavailableException;
import com.nequi.ticketing.domain.port.OrderQueue;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Producer. The message body is only the order id: no personal data travels through the queue. */
public class SqsOrderQueue implements OrderQueue {

    private static final Logger LOG = LoggerFactory.getLogger(SqsOrderQueue.class);

    private final SqsAsyncClient client;
    private final SqsQueueResolver resolver;
    private final int maxRetries;
    private final Duration backoff;

    public SqsOrderQueue(SqsAsyncClient client, SqsQueueResolver resolver, int maxRetries, Duration backoff) {
        this.client = client;
        this.resolver = resolver;
        this.maxRetries = maxRetries;
        this.backoff = backoff;
    }

    @Override
    public Mono<Void> publish(String orderId) {
        return resolver.url()
                .flatMap(url -> Mono.fromFuture(() -> client.sendMessage(
                        SendMessageRequest.builder().queueUrl(url).messageBody(orderId).build())))
                .retryWhen(Retry.backoff(maxRetries, backoff).jitter(0.3).onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnError(e -> LOG.error("Could not enqueue order {}: {}", orderId, e.toString()))
                .onErrorMap(e -> new QueueUnavailableException("Order queue is unavailable", e))
                .then();
    }
}
