package com.wallet.controller;

import com.wallet.dto.WalletResponse;
import com.wallet.model.Wallet;
import com.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /wallets
     * Get-or-create wallet for the authenticated user.
     * The user_id is extracted from the Authorization: Bearer <user_id> header.
     * Always returns HTTP 201 Created with wallet id + balance.
     * Idempotent: 50 concurrent calls yield exactly 1 wallet record.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreateWallet(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        Wallet wallet = walletService.getOrCreate(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    /**
     * GET /wallets/{id}
     * Retrieve current balance and details for a wallet.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("id") UUID id) {
        Wallet wallet = walletService.getById(id);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
