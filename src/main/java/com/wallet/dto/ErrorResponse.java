package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @JsonProperty("error")          String error,
        @JsonProperty("message")        String message,
        @JsonProperty("correlation_id") String correlationId
) {}

