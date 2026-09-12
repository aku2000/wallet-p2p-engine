package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wallet.model.Transfer;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        @JsonProperty("id")               String id,
        @JsonProperty("from_wallet_id")   String fromWalletId,
        @JsonProperty("to_wallet_id")     String toWalletId,
        @JsonProperty("amount_paise")     long amountPaise,
        @JsonProperty("status")           String status,
        @JsonProperty("idempotency_key")  String idempotencyKey,
        @JsonProperty("note")             String note,
        @JsonProperty("reversed_by")      String reversedBy,
        @JsonProperty("created_at")       Instant createdAt
) {
    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.id().toString(),
                t.fromWalletId().toString(),
                t.toWalletId().toString(),
                t.amountPaise(),
                t.status().name().toLowerCase(),
                t.idempotencyKey(),
                t.note(),
                t.reversedBy() != null ? t.reversedBy().toString() : null,
                t.createdAt()
        );
    }
}

