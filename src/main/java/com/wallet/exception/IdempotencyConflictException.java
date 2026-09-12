package com.wallet.exception;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {
    private final String idempotencyKey;
    private final UUID existingTransferId;

    public IdempotencyConflictException(String idempotencyKey, UUID existingTransferId) {
        super("Idempotency key reused with different request body: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
        this.existingTransferId = existingTransferId;
    }

    public String getIdempotencyKey()     { return idempotencyKey; }
    public UUID getExistingTransferId()   { return existingTransferId; }
}

