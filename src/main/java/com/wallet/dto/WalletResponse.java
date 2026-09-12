package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wallet.model.Wallet;

import java.time.Instant;

public record WalletResponse(
        @JsonProperty("id")         String id,
        @JsonProperty("user_id")    String userId,
        @JsonProperty("balance")    long balance,
        @JsonProperty("created_at") Instant createdAt
) {
    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.id().toString(), w.userId(), w.balance(), w.createdAt());
    }
}

