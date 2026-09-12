package com.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer domain counters for the wallet service.
 *
 * These power the /dashboard HTML page and /actuator/metrics endpoint.
 * Counters are cumulative since process start — appropriate for dashboard display.
 *
 * Available metrics:
 *   transfers.completed        - successfully executed transfers
 *   transfers.declined         - declined due to insufficient funds
 *   transfers.idempotent_replay - same idempotency_key returned cached result
 *   wallets.created            - new wallets created (not get, only create)
 */
@Component
public class WalletMetrics {

    private final Counter transfersCompleted;
    private final Counter transfersDeclined;
    private final Counter transfersIdempotentReplay;
    private final Counter walletsCreated;

    public WalletMetrics(MeterRegistry registry) {
        this.transfersCompleted = Counter.builder("transfers.completed")
                .description("Number of successfully executed transfers")
                .tag("status", "completed")
                .register(registry);

        this.transfersDeclined = Counter.builder("transfers.declined")
                .description("Number of transfers declined due to insufficient funds")
                .tag("status", "declined")
                .tag("reason", "insufficient_funds")
                .register(registry);

        this.transfersIdempotentReplay = Counter.builder("transfers.idempotent_replay")
                .description("Number of idempotent replay hits (same key, same body)")
                .tag("status", "replay")
                .register(registry);

        this.walletsCreated = Counter.builder("wallets.created")
                .description("Number of new wallets created")
                .register(registry);
    }

    public void incrementCompleted()        { transfersCompleted.increment(); }
    public void incrementDeclined()         { transfersDeclined.increment(); }
    public void incrementIdempotentReplay() { transfersIdempotentReplay.increment(); }
    public void incrementWalletCreated()    { walletsCreated.increment(); }

    // Getters for dashboard controller to display current counts
    public double getTransfersCompleted()        { return transfersCompleted.count(); }
    public double getTransfersDeclined()         { return transfersDeclined.count(); }
    public double getTransfersIdempotentReplay() { return transfersIdempotentReplay.count(); }
    public double getWalletsCreated()            { return walletsCreated.count(); }
}

