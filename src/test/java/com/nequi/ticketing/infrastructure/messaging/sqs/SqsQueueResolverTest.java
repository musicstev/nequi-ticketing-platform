package com.nequi.ticketing.infrastructure.messaging.sqs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

class SqsQueueResolverTest {

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final SqsQueueResolver resolver = new SqsQueueResolver(client, "orders-queue");

    @Test
    void resolvesTheUrlOnceAndCachesIt() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueueUrlResponse.builder().queueUrl("http://q/orders-queue").build()));

        StepVerifier.create(resolver.url()).expectNext("http://q/orders-queue").verifyComplete();
        StepVerifier.create(resolver.url()).expectNext("http://q/orders-queue").verifyComplete();
        verify(client, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void failuresAreNotCachedSoALaterCallCanRecover() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("not ready")))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueueUrlResponse.builder().queueUrl("http://q/orders-queue").build()));

        StepVerifier.create(resolver.url()).expectError(IllegalStateException.class).verify();
        StepVerifier.create(resolver.url()).expectNext("http://q/orders-queue").verifyComplete();
    }
}
