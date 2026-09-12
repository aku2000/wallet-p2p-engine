package com.wallet.repository;

import com.wallet.model.Transfer;
import com.wallet.model.TransferStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Transfer> TRANSFER_ROW_MAPPER = (rs, rowNum) -> new Transfer(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("from_wallet_id")),
            UUID.fromString(rs.getString("to_wallet_id")),
            rs.getLong("amount_paise"),
            TransferStatus.valueOf(rs.getString("status").toUpperCase()),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getString("note"),
            rs.getString("reversed_by") != null ? UUID.fromString(rs.getString("reversed_by")) : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    /**
     * Inserts a transfer row with status=PENDING.
     *
     * This is the idempotency insertion point. The UNIQUE constraint on
     * idempotency_key means that if another request with the same key is
     * in-flight or already committed, this INSERT will throw DuplicateKeyException.
     *
     * Concurrent behavior (PostgreSQL unique index semantics):
     * - If a concurrent tx has the same key in-flight (not yet committed):
     *   this INSERT BLOCKS until that tx commits or rolls back.
     * - If the concurrent tx committed: DuplicateKeyException is thrown immediately.
     * - If the concurrent tx rolled back: this INSERT proceeds (key is free).
     *
     * This blocking behavior is what makes idempotency atomic with the ledger write.
     * The caller (TransferService) catches DuplicateKeyException OUTSIDE the transaction.
     *
     * @throws DuplicateKeyException if idempotency_key already exists
     */
    public void insertPending(UUID id, UUID fromWalletId, UUID toWalletId,
                              long amountPaise, String idempotencyKey,
                              String requestHash, String note) {
        jdbc.update(
                "INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount_paise, " +
                "status, idempotency_key, request_hash, note) " +
                "VALUES (?, ?, ?, ?, 'pending', ?, ?, ?)",
                id.toString(),
                fromWalletId.toString(),
                toWalletId.toString(),
                amountPaise,
                idempotencyKey,
                requestHash,
                note
        );
        // DuplicateKeyException propagates to caller if idempotency_key conflicts
    }

    public void updateStatus(UUID id, TransferStatus status) {
        jdbc.update(
                "UPDATE transfers SET status = ?, updated_at = NOW() WHERE id = ?",
                status.name().toLowerCase(), id.toString()
        );
    }

    @Transactional(readOnly = true)
    public Optional<Transfer> findById(UUID id) {
        List<Transfer> results = jdbc.query(
                "SELECT id, from_wallet_id, to_wallet_id, amount_paise, status, " +
                "idempotency_key, request_hash, note, reversed_by, created_at, updated_at " +
                "FROM transfers WHERE id = ?",
                TRANSFER_ROW_MAPPER, id.toString()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Fetches an existing transfer by idempotency key.
     * Called AFTER a DuplicateKeyException rolls back the current transaction,
     * so this runs in a fresh READ COMMITTED read — it sees the committed row.
     */
    @Transactional(readOnly = true)
    public Optional<Transfer> findByIdempotencyKey(String key) {
        List<Transfer> results = jdbc.query(
                "SELECT id, from_wallet_id, to_wallet_id, amount_paise, status, " +
                "idempotency_key, request_hash, note, reversed_by, created_at, updated_at " +
                "FROM transfers WHERE idempotency_key = ?",
                TRANSFER_ROW_MAPPER, key
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}

