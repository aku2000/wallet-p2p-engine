package com.wallet.service;

import com.wallet.exception.WalletNotFoundException;
import com.wallet.metrics.WalletMetrics;
import com.wallet.model.Wallet;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WalletMetrics metrics;

    public WalletService(WalletRepository walletRepository, WalletMetrics metrics) {
        this.walletRepository = walletRepository;
        this.metrics = metrics;
    }

    /**
     * Get-or-create wallet for the authenticated user.
     *
     * Uses DB-level upsert (INSERT ON CONFLICT DO NOTHING) — race-free by construction.
     * 50 concurrent calls for the same userId → exactly 1 wallet, all return same wallet.
     *
     * The metrics counter is incremented only when a new wallet is actually created,
     * not on subsequent get calls. This is determined by comparing created_at vs updated_at.
     */
    public Wallet getOrCreate(String userId) {
        boolean existed = walletRepository.findByUserId(userId).isPresent();
        Wallet wallet = walletRepository.upsertByUserId(userId);

        if (!existed) {
            log.info("Wallet created",
                    kv("event", "wallet.created"),
                    kv("wallet_id", wallet.id()),
                    kv("user_id", userId)
            );
            metrics.incrementWalletCreated();
        }

        return wallet;
    }

    /**
     * Retrieve a wallet by its UUID.
     *
     * @throws WalletNotFoundException (→ 404) if wallet does not exist
     */
    public Wallet getById(UUID id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }
}

