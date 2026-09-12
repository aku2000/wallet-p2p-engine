package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateTransferRequest(
        @JsonProperty("from")          String from,
        @JsonProperty("to")            String to,
        @JsonProperty("amount_paise")  long amountPaise,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("note")          String note
) {}

