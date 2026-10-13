package com.nequi.ticketing.infrastructure.scheduler;

import com.nequi.ticketing.application.ReleaseExpiredReservationsUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Periodically releases expired reservations. {@code concatMap} guarantees runs never overlap inside
 * one instance and {@code onBackpressureDrop} skips ticks while a run is still in progress. Across
 * instances overlap is harmless: each release is a conditional write that only one instance can win.
 */
public class ExpiryScheduler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final ReleaseExpiredReservationsUseCase releaseExpired;
    private final Duration interval;
    private volatile Disposable subscription;

    public ExpiryScheduler(ReleaseExpiredReservationsUseCase releaseExpired, Duration interval) {
        this.releaseExpired = releaseExpired;
        this.interval = interval;
    }

    Flux<Integer> runs() {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(tick -> releaseExpired
                        .execute()
                        .doOnNext(released -> {
                            if (released > 0) {
                                LOG.info("Released {} expired reservation(s)", released);
                            }
                        })
                        .onErrorResume(error -> {
                            LOG.error("Expiry run failed, will retry on next tick: {}", error.toString());
                            return Mono.empty();
                        }));
    }

    @Override
    public synchronized void start() {
        if (!isRunning()) {
            subscription = runs().subscribe();
        }
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }
}
