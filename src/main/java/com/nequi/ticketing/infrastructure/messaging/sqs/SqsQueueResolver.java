package com.nequi.ticketing.infrastructure.messaging.sqs;

import java.time.Duration;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

/** Lazily resolves (and caches) the queue URL from its name; failures are not cached. */
public class SqsQueueResolver {

    private static final Duration CACHE_TTL = Duration.ofDays(365);

    private final Mono<String> url;

    public SqsQueueResolver(SqsAsyncClient client, String queueName) {
        this.url = Mono.fromFuture(
                        () -> client.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()))
                .map(GetQueueUrlResponse::queueUrl)
                .cache(value -> CACHE_TTL, error -> Duration.ZERO, () -> Duration.ZERO);
    }

    public Mono<String> url() {
        return url;
    }
}
