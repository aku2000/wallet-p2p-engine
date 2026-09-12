package com.wallet.model;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        TransferStatus status,
        String idempotencyKey,
        String requestHash,
        String note,
        UUID reversedBy,
        Instant createdAt,
        Instant updatedAt
) {}

