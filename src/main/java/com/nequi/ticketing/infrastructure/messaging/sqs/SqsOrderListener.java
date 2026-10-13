package com.nequi.ticketing.infrastructure.messaging.sqs;

import com.nequi.ticketing.application.ProcessOrderUseCase;
import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.DomainException;
import com.nequi.ticketing.domain.exception.QueueUnavailableException;
import com.nequi.ticketing.domain.port.OrderMetrics;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * At-least-once consumer. A message is deleted ONLY after the order was processed; any failure leaves it
 * in the queue, so SQS redelivers it after the visibility timeout and, once maxReceiveCount is exceeded,
 * moves it to the dead-letter queue. Redeliveries are harmless because {@link ProcessOrderUseCase} is idempotent.
 */
public class SqsOrderListener implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SqsOrderListener.class);
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Duration MAX_POLL_BACKOFF = Duration.ofSeconds(30);

    private final SqsAsyncClient client;
    private final SqsQueueResolver resolver;
    private final ProcessOrderUseCase processOrder;
    private final OrderMetrics metrics;
    private final SqsListenerSettings settings;
    private volatile Disposable subscription;

    public SqsOrderListener(
            SqsAsyncClient client,
            SqsQueueResolver resolver,
            ProcessOrderUseCase processOrder,
            OrderMetrics metrics,
            SqsListenerSettings settings) {
        this.client = client;
        this.resolver = resolver;
        this.processOrder = processOrder;
        this.metrics = metrics;
        this.settings = settings;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        subscription = resolver.url()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, settings.pollErrorBackoff()).maxBackoff(MAX_POLL_BACKOFF))
                .flatMapMany(url -> poll(url).flatMap(message -> handle(url, message), settings.concurrency()))
                .subscribe(ignored -> {}, error -> LOG.error("Order listener terminated unexpectedly", error));
        LOG.info("SQS order listener started");
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
            LOG.info("SQS order listener stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }

    /** Endless long-poll loop; receive errors back off exponentially instead of spinning. */
    Flux<Message> poll(String queueUrl) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(settings.maxMessages())
                .waitTimeSeconds(settings.waitTimeSeconds())
                .visibilityTimeout(settings.visibilityTimeoutSeconds())
                .build();
        return Mono.fromFuture(() -> client.receiveMessage(request))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, settings.pollErrorBackoff())
                        .maxBackoff(MAX_POLL_BACKOFF)
                        .doBeforeRetry(signal -> LOG.warn("Receive failed, retrying: {}", signal.failure().toString())))
                .flatMapIterable(ReceiveMessageResponse::messages)
                .repeat();
    }

    Mono<Void> handle(String queueUrl, Message message) {
        return Mono.fromSupplier(() -> orderIdOf(message))
                .flatMap(processOrder::execute)
                .retryWhen(Retry.backoff(settings.processingRetries(), settings.retryBackoff())
                        .filter(SqsOrderListener::isTransient)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .then(Mono.defer(() -> delete(queueUrl, message)))
                .onErrorResume(error -> {
                    // Body is never logged: only ids and the error type.
                    LOG.error("Order message {} not processed, left for redelivery: {}", message.messageId(), error.toString());
                    metrics.orderFailed();
                    return Mono.empty();
                });
    }

    private Mono<Void> delete(String queueUrl, Message message) {
        return Mono.fromFuture(() -> client.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build()))
                .then()
                .onErrorResume(error -> {
                    LOG.warn("Could not delete message {} (it will be redelivered and ignored): {}",
                            message.messageId(), error.toString());
                    return Mono.empty();
                });
    }

    private static String orderIdOf(Message message) {
        String body = message.body();
        if (body == null || !ORDER_ID.matcher(body).matches()) {
            throw new IllegalArgumentException("Malformed order message");
        }
        return body;
    }

    /** Infrastructure hiccups and optimistic-lock conflicts are worth retrying; business errors and poison messages are not. */
    private static boolean isTransient(Throwable error) {
        return switch (error) {
            case ConcurrentUpdateException e -> true;
            case QueueUnavailableException e -> true;
            case DomainException e -> false;
            case IllegalArgumentException e -> false;
            default -> true;
        };
    }
}
