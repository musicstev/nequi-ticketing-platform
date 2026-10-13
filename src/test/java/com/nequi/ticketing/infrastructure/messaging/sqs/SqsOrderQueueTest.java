package com.nequi.ticketing.infrastructure.messaging.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.QueueUnavailableException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsOrderQueueTest {

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final SqsQueueResolver resolver = mock(SqsQueueResolver.class);
    private final SqsOrderQueue queue = new SqsOrderQueue(client, resolver, 2, Duration.ofMillis(1));

    @BeforeEach
    void stubUrl() {
        when(resolver.url()).thenReturn(Mono.just("http://q/orders"));
    }

    @Test
    void publishesTheOrderIdAsMessageBody() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m1").build()));

        StepVerifier.create(queue.publish("order-1")).verifyComplete();

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo("http://q/orders");
        assertThat(request.getValue().messageBody()).isEqualTo("order-1");
    }

    @Test
    void retriesTransientFailuresWithBackoff() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("throttled")))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m1").build()));

        StepVerifier.create(queue.publish("order-1")).verifyComplete();
        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void reportsQueueUnavailableAfterBoundedRetries() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        StepVerifier.create(queue.publish("order-1")).expectError(QueueUnavailableException.class).verify();
        verify(client, times(3)).sendMessage(any(SendMessageRequest.class));
    }
}
