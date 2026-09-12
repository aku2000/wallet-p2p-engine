package com.wallet.model;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID transferId,
        UUID walletId,
        String direction,     // "debit" or "credit"
        long amountPaise,
        long balanceBefore,
        long balanceAfter,
        Instant createdAt
) {}

