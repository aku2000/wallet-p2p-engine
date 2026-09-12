package com.wallet.controller;

import com.wallet.dto.CreateTransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.model.Transfer;
import com.wallet.model.TransferStatus;
import com.wallet.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /transfers
     * Move money from one wallet to another.
     * Body: from, to, amount_paise, idempotency_key, note (optional).
     *
     * Responses:
     * - 201 Created: Transfer newly executed and completed
     * - 200 OK: Idempotent replay of previously completed transfer
     * - 422 Unprocessable Entity: Transfer declined (e.g. insufficient funds)
     * - 400 Bad Request: Invalid input / self-transfer / malformed UUID
     * - 401 Unauthorized: Missing or invalid Bearer token
     * - 403 Forbidden: Caller does not own the 'from' wallet
     * - 404 Not Found: from or to wallet not found
     * - 409 Conflict: Same idempotency_key reused with different body
     */
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestBody CreateTransferRequest request,
            HttpServletRequest httpRequest) {

        String userId = (String) httpRequest.getAttribute("userId");
        Transfer transfer = transferService.executeTransfer(request, userId);

        TransferResponse responseBody = TransferResponse.from(transfer);

        if (transfer.status() == TransferStatus.DECLINED) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(responseBody);
        }

        // Return 200 for idempotent replay, 201 for new execution
        return ResponseEntity.status(HttpStatus.CREATED).body(responseBody);
    }

    /**
     * GET /transfers/{id}
     * Retrieve status and details of a transfer.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable("id") UUID id) {
        Transfer transfer = transferService.getById(id);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }
}
