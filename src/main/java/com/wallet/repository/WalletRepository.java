package com.wallet.repository;

import com.wallet.model.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
            UUID.fromString(rs.getString("id")),
            rs.getString("user_id"),
            rs.getLong("balance"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    @Transactional(readOnly = true)
    public Optional<Wallet> findById(UUID id) {
        List<Wallet> results = jdbc.query(
                "SELECT id, user_id, balance, created_at, updated_at FROM wallets WHERE id = ?",
                WALLET_ROW_MAPPER, id.toString()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Transactional(readOnly = true)
    public Optional<Wallet> findByUserId(String userId) {
        List<Wallet> results = jdbc.query(
                "SELECT id, user_id, balance, created_at, updated_at FROM wallets WHERE user_id = ?",
                WALLET_ROW_MAPPER, userId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Race-free get-or-create using INSERT ... ON CONFLICT (user_id) DO NOTHING.
     *
     * The UNIQUE index on user_id is the actual race guard — not application logic.
     * 50 concurrent requests for the same user_id → exactly 1 wallet created.
     *
     * Why NOT check-then-insert: classic TOCTOU race — two concurrent requests
     * both see "no wallet", both INSERT, result is two wallets or a 500 crash.
     */
    @Transactional
    public Wallet upsertByUserId(String userId) {
        jdbc.update(
                "INSERT INTO wallets (id, user_id, balance) VALUES (gen_random_uuid(), ?, 0) ON CONFLICT (user_id) DO NOTHING",
                userId
        );
        return findByUserId(userId).orElseThrow(() ->
                new IllegalStateException("Wallet not found after upsert for user: " + userId));
    }

    /**
     * Locks wallets in deterministic ascending UUID order to prevent deadlocks.
     *
     * WHY TWO SEPARATE QUERIES instead of "WHERE id IN (a,b) ORDER BY id FOR UPDATE":
     * A single multi-row FOR UPDATE may acquire locks in heap/index order, which is not
     * guaranteed to match UUID sort order across all query plans and Postgres versions.
     * Two explicit queries with lower UUID first ensures A→B and B→A both lock
     * the same wallet first — deadlock is structurally impossible.
     *
     * @return [lowerIdWallet, higherIdWallet] (always in ascending UUID order)
     */
    public List<Wallet> lockInSortedOrder(UUID id1, UUID id2) {
        UUID lowerId  = id1.compareTo(id2) <= 0 ? id1 : id2;
        UUID higherId = id1.compareTo(id2) <= 0 ? id2 : id1;

        Wallet low = jdbc.queryForObject(
                "SELECT id, user_id, balance, created_at, updated_at FROM wallets WHERE id = ? FOR UPDATE",
                WALLET_ROW_MAPPER, lowerId.toString()
        );
        Wallet high = jdbc.queryForObject(
                "SELECT id, user_id, balance, created_at, updated_at FROM wallets WHERE id = ? FOR UPDATE",
                WALLET_ROW_MAPPER, higherId.toString()
        );
        // Both rows are now locked for the duration of the calling transaction
        return List.of(low, high);
    }

    /**
     * Adjusts wallet balance by delta.
     * Use negative delta for debit, positive for credit.
     * The CHECK (balance >= 0) constraint is a last-resort DB safety net.
     */
    public void adjustBalance(UUID walletId, long delta) {
        jdbc.update(
                "UPDATE wallets SET balance = balance + ?, updated_at = NOW() WHERE id = ?",
                delta, walletId.toString()
        );
    }
}
