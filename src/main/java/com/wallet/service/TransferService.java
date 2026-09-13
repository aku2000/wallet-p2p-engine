package com.wallet.service;

import com.wallet.dto.CreateTransferRequest;
import com.wallet.exception.ForbiddenException;
import com.wallet.exception.IdempotencyConflictException;
import com.wallet.exception.InvalidRequestException;
import com.wallet.exception.TransferNotFoundException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.metrics.WalletMetrics;
import com.wallet.model.Transfer;
import com.wallet.model.TransferStatus;
import com.wallet.model.Wallet;
import com.wallet.repository.LedgerRepository;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * TransferService — the core of all financial invariant enforcement.
 *
 * INVARIANTS MAINTAINED:
 * 1. Conservation: total balance across all wallets never changes during a transfer.
 * 2. No overdraft: sender balance never goes negative.
 * 3. Exactly-once: same idempotency_key always yields the same result, applied once.
 * 4. Race-free: concurrent conflicting transfers are serialized via sorted FOR UPDATE.
 *
 * CONCURRENCY MECHANISM:
 * All steps run in a single PostgreSQL transaction via TransactionTemplate.
 * Wallets are locked with SELECT FOR UPDATE in ascending UUID order (deadlock-proof).
 * The idempotency_key UNIQUE constraint is committed atomically with the balance update.
 *
 * See docs/decisions/ADR-001 and ADR-002 for full rationale.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerRepository ledgerRepository;
    private final TransactionTemplate transactionTemplate;
    private final WalletMetrics metrics;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           LedgerRepository ledgerRepository,
                           TransactionTemplate transactionTemplate,
                           WalletMetrics metrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    /**
     * Executes a P2P transfer with all financial invariants enforced.
     *
     * TRANSACTION FLOW (all steps inside one atomic transaction):
     *   1. INSERT transfer row with status=pending (UNIQUE on idempotency_key)
     *      → DuplicateKeyException = idempotent replay, caught OUTSIDE this tx
     *   2. SELECT FOR UPDATE on both wallets (sorted by UUID, deadlock-proof)
     *   3. Check sender.balance >= amountPaise
     *      → If not: mark DECLINED, commit, return (no money moved)
     *   4. adjustBalance(sender, -amount)     — debit
     *   5. adjustBalance(receiver, +amount)   — credit
     *   6. INSERT ledger entry: debit  (balance_before/after snapshot)
     *   7. INSERT ledger entry: credit (balance_before/after snapshot)
     *   8. UPDATE transfer status → COMPLETED
     *
     * IDEMPOTENCY:
     *   DuplicateKeyException propagates OUT of transactionTemplate.execute(),
     *   which causes Spring to roll back the transaction before rethrowing.
     *   We catch it in the outer try-catch, re-fetch the existing committed row,
     *   compare request hash, and return the original result (or 409 if body differs).
     *
     * @param request       validated transfer request
     * @param callerUserId  user_id of the authenticated caller (from Bearer token)
     * @return the Transfer domain object (completed, declined, or existing replay)
     */
    public Transfer executeTransfer(CreateTransferRequest request, String callerUserId) {
        // --- Input validation ---
        if (request.amountPaise() <= 0) {
            throw new InvalidRequestException(
                    "amount_paise must be positive, got: " + request.amountPaise());
        }
        if (request.from() == null || request.from().isBlank()) {
            throw new InvalidRequestException("from wallet ID is required");
        }
        if (request.to() == null || request.to().isBlank()) {
            throw new InvalidRequestException("to wallet ID is required");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new InvalidRequestException("idempotency_key is required");
        }

        UUID fromWalletId;
        UUID toWalletId;
        try {
            fromWalletId = UUID.fromString(request.from());
            toWalletId   = UUID.fromString(request.to());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("from and to must be valid UUIDs");
        }

        if (fromWalletId.equals(toWalletId)) {
            throw new InvalidRequestException("Self-transfer is not allowed");
        }

        // --- Authorization: caller must own the from wallet ---
        Wallet fromWallet = walletRepository.findById(fromWalletId)
                .orElseThrow(() -> new WalletNotFoundException(fromWalletId));

        if (!fromWallet.userId().equals(callerUserId)) {
            throw new ForbiddenException(
                    "Authenticated user '" + callerUserId + "' does not own wallet " + fromWalletId);
        }

        // --- Verify destination wallet exists ---
        walletRepository.findById(toWalletId)
                .orElseThrow(() -> new WalletNotFoundException(toWalletId));

        // --- Compute canonical request hash for idempotency conflict detection ---
        // Same key + same hash → replay. Same key + different hash → 409 Conflict.
        String requestHash = computeRequestHash(
                request.from(), request.to(), request.amountPaise(), request.idempotencyKey());

        UUID transferId = UUID.randomUUID();

        try {
            // All steps inside a single database transaction
            Transfer result = transactionTemplate.execute(txStatus -> {

                // STEP 1: Lock both wallets in ascending UUID order.
                // This is the deadlock-prevention mechanism:
                // A→B and B→A both acquire lock on the lower UUID first.
                // One blocks, the other commits, then the blocked one proceeds with fresh data.
                // Crucially, locking occurs BEFORE inserting the transfer row so that PostgreSQL
                // foreign key validation does not acquire uncoordinated locks in conflicting order.
                List<Wallet> locked = walletRepository.lockInSortedOrder(fromWalletId, toWalletId);
                Wallet sender   = findFromList(locked, fromWalletId);
                Wallet receiver = findFromList(locked, toWalletId);

                // STEP 2: Insert transfer row (pending).
                // If idempotency_key already exists → DuplicateKeyException thrown here.
                // Caught outside transactionTemplate.execute() for idempotent replay.
                transferRepository.insertPending(
                        transferId, fromWalletId, toWalletId,
                        request.amountPaise(), request.idempotencyKey(), requestHash, request.note()
                );

                // STEP 3: Balance check on the LOCKED row (not a stale pre-lock read).
                if (sender.balance() < request.amountPaise()) {
                    // No money moves. Mark declined and commit so future replays see the result.
                    transferRepository.updateStatus(transferId, TransferStatus.DECLINED);

                    log.info("Transfer declined: insufficient funds",
                            kv("event", "transfer.declined"),
                            kv("transfer_id", transferId),
                            kv("reason", "insufficient_funds"),
                            kv("sender_balance", sender.balance()),
                            kv("requested_amount", request.amountPaise())
                    );
                    metrics.incrementDeclined();

                    return transferRepository.findById(transferId).orElseThrow();
                }

                // Snapshot balances BEFORE the update (for ledger entries)
                long senderBalanceBefore   = sender.balance();
                long receiverBalanceBefore = receiver.balance();

                // STEP 4: Debit sender (negative delta)
                walletRepository.adjustBalance(fromWalletId, -request.amountPaise());

                // STEP 5: Credit receiver (positive delta)
                walletRepository.adjustBalance(toWalletId, +request.amountPaise());

                // STEP 6 & 7: Double-entry ledger (append-only, both entries in same tx)
                ledgerRepository.insertEntry(
                        transferId, fromWalletId, "debit",
                        request.amountPaise(),
                        senderBalanceBefore,
                        senderBalanceBefore - request.amountPaise()   // balance_after
                );
                ledgerRepository.insertEntry(
                        transferId, toWalletId, "credit",
                        request.amountPaise(),
                        receiverBalanceBefore,
                        receiverBalanceBefore + request.amountPaise() // balance_after
                );

                // STEP 8: Mark completed
                transferRepository.updateStatus(transferId, TransferStatus.COMPLETED);

                Transfer completed = transferRepository.findById(transferId).orElseThrow();

                log.info("Transfer completed",
                        kv("event", "transfer.completed"),
                        kv("transfer_id", transferId),
                        kv("from_wallet", fromWalletId),
                        kv("to_wallet", toWalletId),
                        kv("amount_paise", request.amountPaise()),
                        kv("sender_balance_after",   senderBalanceBefore   - request.amountPaise()),
                        kv("receiver_balance_after", receiverBalanceBefore + request.amountPaise())
                );
                metrics.incrementCompleted();

                return completed;
            });

            return result;

        } catch (DuplicateKeyException e) {
            // The transaction was automatically rolled back by Spring before this catch runs.
            // The idempotency_key already exists in DB (committed by a previous request).
            // Re-fetch the existing committed row in a fresh implicit transaction.
            Transfer existing = transferRepository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key conflict but no committed transfer found: "
                            + request.idempotencyKey()));

            if (!existing.requestHash().equals(requestHash)) {
                // Same key, different request body → 409 Conflict
                log.warn("Idempotency conflict: same key, different body",
                        kv("event", "transfer.idempotency_conflict"),
                        kv("idempotency_key", request.idempotencyKey()),
                        kv("existing_transfer_id", existing.id())
                );
                throw new IdempotencyConflictException(request.idempotencyKey(), existing.id());
            }

            // Same key, same body → idempotent replay, return original result
            log.info("Idempotent replay: returning original transfer",
                    kv("event", "transfer.idempotent_replay"),
                    kv("transfer_id", existing.id()),
                    kv("idempotency_key", request.idempotencyKey()),
                    kv("original_status", existing.status().name().toLowerCase())
            );
            metrics.incrementIdempotentReplay();

            return existing;
        }
    }

    /**
     * Retrieve a transfer by ID.
     *
     * @throws TransferNotFoundException (→ 404) if not found
     */
    public Transfer getById(UUID id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    // --- Helpers ---

    private Wallet findFromList(List<Wallet> wallets, UUID id) {
        return wallets.stream()
                .filter(w -> w.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new WalletNotFoundException(id));
    }

    /**
     * SHA-256 hash of the canonical request body fields.
     * Stored in transfers.request_hash for idempotency conflict detection.
     * Same key + same hash → replay. Same key + different hash → 409.
     */
    private String computeRequestHash(String from, String to, long amountPaise, String idempotencyKey) {
        try {
            String canonical = from + ":" + to + ":" + amountPaise + ":" + idempotencyKey;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

