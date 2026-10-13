package com.nequi.ticketing.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.ReleaseExpiredReservationsUseCase;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExpirySchedulerTest {

    private final ReleaseExpiredReservationsUseCase useCase = mock(ReleaseExpiredReservationsUseCase.class);

    @Test
    void runsTheReleaseOnEveryTick() {
        when(useCase.execute()).thenReturn(Mono.just(2));
        ExpiryScheduler scheduler = new ExpiryScheduler(useCase, Duration.ofSeconds(30));

        StepVerifier.withVirtualTime(() -> scheduler.runs().take(3))
                .thenAwait(Duration.ofSeconds(90))
                .expectNext(2, 2, 2)
                .verifyComplete();
    }

    @Test
    void neverOverlapsRunsAndDropsTicksWhileOneIsStillRunning() {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        when(useCase.execute()).thenReturn(Mono.defer(() -> {
            started.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofSeconds(25)).thenReturn(1).doFinally(s -> concurrent.decrementAndGet());
        }));
        ExpiryScheduler scheduler = new ExpiryScheduler(useCase, Duration.ofSeconds(10));

        StepVerifier.withVirtualTime(() -> scheduler.runs().take(2))
                .thenAwait(Duration.ofSeconds(120))
                .expectNext(1, 1)
                .verifyComplete();

        assertThat(maxConcurrent.get()).isEqualTo(1);
        assertThat(started.get()).isLessThanOrEqualTo(3); // 25s runs on a 10s timer: ticks were dropped
    }

    @Test
    void aFailingRunDoesNotStopTheSchedule() {
        when(useCase.execute()).thenReturn(Mono.error(new IllegalStateException("dynamo down")), Mono.just(5));
        ExpiryScheduler scheduler = new ExpiryScheduler(useCase, Duration.ofSeconds(10));

        StepVerifier.withVirtualTime(() -> scheduler.runs().take(1))
                .thenAwait(Duration.ofSeconds(30))
                .expectNext(5)
                .verifyComplete();
        verify(useCase, times(2)).execute();
    }

    @Test
    void lifecycleStartsAndStops() {
        when(useCase.execute()).thenReturn(Mono.just(0));
        ExpiryScheduler scheduler = new ExpiryScheduler(useCase, Duration.ofMillis(10));

        assertThat(scheduler.isRunning()).isFalse();
        scheduler.start();
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        verify(useCase, org.mockito.Mockito.timeout(2000).atLeast(2)).execute();
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
        scheduler.stop();
    }
}
