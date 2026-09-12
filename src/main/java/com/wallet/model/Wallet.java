package com.wallet.model;

import java.time.Instant;
import java.util.UUID;

public record Wallet(
        UUID id,
        String userId,
        long balance,      // in paise — always integer, never float
        Instant createdAt,
        Instant updatedAt
) {}

