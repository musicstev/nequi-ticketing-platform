package com.nequi.ticketing.support;

import com.nequi.ticketing.domain.port.OrderMetrics;
import java.util.concurrent.atomic.AtomicInteger;

public class RecordingMetrics implements OrderMetrics {

    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger confirmed = new AtomicInteger();
    private final AtomicInteger expired = new AtomicInteger();

    @Override
    public void orderProcessed() {
        processed.incrementAndGet();
    }

    @Override
    public void orderRejected() {
        rejected.incrementAndGet();
    }

    @Override
    public void orderFailed() {
        failed.incrementAndGet();
    }

    @Override
    public void orderConfirmed() {
        confirmed.incrementAndGet();
    }

    @Override
    public void reservationsExpired(int count) {
        expired.addAndGet(count);
    }

    public int processed() {
        return processed.get();
    }

    public int rejected() {
        return rejected.get();
    }

    public int expired() {
        return expired.get();
    }
}
