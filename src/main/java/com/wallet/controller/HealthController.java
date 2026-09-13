package com.wallet.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
public class HealthController {

    /**
     * GET /
     * Redirects browser visitors directly to the live dashboard.
     */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/dashboard"))
                .build();
    }

    /**
     * GET /health
     * Public liveness and readiness probe for Render and Docker HEALTHCHECK.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "wallet-p2p-engine"
        ));
    }
}
