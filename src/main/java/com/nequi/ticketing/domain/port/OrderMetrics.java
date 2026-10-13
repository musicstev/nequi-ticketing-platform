package com.nequi.ticketing.domain.port;

public interface OrderMetrics {

    void orderProcessed();

    void orderRejected();

    void orderFailed();

    void orderConfirmed();

    void reservationsExpired(int count);
}
