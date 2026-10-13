package com.nequi.ticketing.infrastructure.messaging.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.ProcessOrderUseCase;
import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.port.OrderMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsOrderListenerTest {

    private static final Order ORDER = Order.pending("order-1", "e1", "c1", 1, "c1#k", Instant.parse("2030-01-01T10:00:00Z"));

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final SqsQueueResolver resolver = mock(SqsQueueResolver.class);
    private final ProcessOrderUseCase processOrder = mock(ProcessOrderUseCase.class);
    private final OrderMetrics metrics = mock(OrderMetrics.class);
    private final SqsListenerSettings settings =
            new SqsListenerSettings(1, 10, 4, 30, 2, Duration.ofMillis(1), Duration.ofMillis(1));
    private final SqsOrderListener listener = new SqsOrderListener(client, resolver, processOrder, metrics, settings);

    private static Message message(String body) {
        return Message.builder().messageId("m-" + body).receiptHandle("rh-" + body).body(body).build();
    }

    private void deleteWorks() {
        when(resolver.url()).thenReturn(Mono.just("http://q/orders"));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    }

    @Test
    void deletesTheMessageOnlyAfterSuccessfulProcessing() {
        deleteWorks();
        when(processOrder.execute("order-1")).thenReturn(Mono.just(ORDER));

        StepVerifier.create(listener.handle("http://q/orders", message("order-1"))).verifyComplete();

        ArgumentCaptor<DeleteMessageRequest> delete = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client).deleteMessage(delete.capture());
        assertThat(delete.getValue().receiptHandle()).isEqualTo("rh-order-1");
        assertThat(delete.getValue().queueUrl()).isEqualTo("http://q/orders");
    }

    @Test
    void keepsTheMessageWhenProcessingFailsSoSqsRedeliversAndEventuallyDeadLetters() {
        deleteWorks();
        when(processOrder.execute("order-1")).thenReturn(Mono.error(new OrderNotFoundException("order-1")));

        StepVerifier.create(listener.handle("http://q/orders", message("order-1"))).verifyComplete();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(processOrder, times(1)).execute("order-1"); // business errors are not retried in-process
        verify(metrics).orderFailed();
    }

    @Test
    void retriesTransientConflictsInProcessBeforeDeleting() {
        deleteWorks();
        when(processOrder.execute("order-1"))
                .thenReturn(Mono.error(new ConcurrentUpdateException("conflict")))
                .thenReturn(Mono.error(new ConcurrentUpdateException("conflict")))
                .thenReturn(Mono.just(ORDER));

        StepVerifier.create(listener.handle("http://q/orders", message("order-1"))).verifyComplete();

        verify(processOrder, times(3)).execute("order-1");
        verify(client).deleteMessage(any(DeleteMessageRequest.class));
        verify(metrics, never()).orderFailed();
    }

    @Test
    void givesUpOnPersistentTransientFailuresWithoutDeleting() {
        deleteWorks();
        when(processOrder.execute("order-1")).thenReturn(Mono.error(new IllegalStateException("dynamo down")));

        StepVerifier.create(listener.handle("http://q/orders", message("order-1"))).verifyComplete();

        verify(processOrder, times(3)).execute("order-1");
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(metrics).orderFailed();
    }

    @Test
    void malformedBodiesAreNeverProcessedNorDeleted() {
        deleteWorks();
        StepVerifier.create(listener.handle("http://q/orders", message("<script>alert(1)</script>"))).verifyComplete();
        verify(processOrder, never()).execute(anyString());
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(metrics).orderFailed();
    }

    @Test
    void aFailedDeleteIsHarmlessBecauseTheConsumerIsIdempotent() {
        when(resolver.url()).thenReturn(Mono.just("http://q/orders"));
        when(processOrder.execute("order-1")).thenReturn(Mono.just(ORDER));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("network")));

        StepVerifier.create(listener.handle("http://q/orders", message("order-1"))).verifyComplete();
    }

    @Test
    void pollEmitsMessagesAndRecoversFromReceiveErrors() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("blip")))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message("a"), message("b"))).build()))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message("c"))).build()));

        StepVerifier.create(listener.poll("http://q/orders").take(3))
                .expectNextMatches(m -> m.body().equals("a"))
                .expectNextMatches(m -> m.body().equals("b"))
                .expectNextMatches(m -> m.body().equals("c"))
                .verifyComplete();

        ArgumentCaptor<ReceiveMessageRequest> request = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client, times(3)).receiveMessage(request.capture());
        assertThat(request.getValue().waitTimeSeconds()).isEqualTo(1);
        assertThat(request.getValue().maxNumberOfMessages()).isEqualTo(10);
        assertThat(request.getValue().visibilityTimeout()).isEqualTo(30);
    }

    @Test
    void lifecycleStartsPollingProcessesMessagesAndStopsCleanly() {
        deleteWorks();
        when(processOrder.execute("order-1")).thenReturn(Mono.just(ORDER));
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message("order-1"))).build()))
                .thenReturn(new CompletableFuture<>()); // long poll that never returns

        assertThat(listener.isRunning()).isFalse();
        listener.start();
        assertThat(listener.isRunning()).isTrue();
        listener.start(); // idempotent

        verify(client, timeout(2000)).deleteMessage(any(DeleteMessageRequest.class));

        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }
}
