package com.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a single ledger entry (debit or credit).
     *
     * The ledger is APPEND-ONLY — this is the only write operation on ledger_entries.
     * Never UPDATE or DELETE a ledger row. Reversals create new offsetting entries.
     *
     * balance_before and balance_after enable point-in-time balance reconstruction
     * for audits, dispute resolution, and the reconciliation check.
     *
     * @param transferId    the transfer this entry belongs to
     * @param walletId      the wallet being debited or credited
     * @param direction     "debit" (money leaving wallet) or "credit" (money arriving)
     * @param amountPaise   the amount moved (always positive)
     * @param balanceBefore wallet balance immediately BEFORE this entry
     * @param balanceAfter  wallet balance immediately AFTER this entry
     */
    public void insertEntry(UUID transferId, UUID walletId, String direction,
                            long amountPaise, long balanceBefore, long balanceAfter) {
        jdbc.update(
                "INSERT INTO ledger_entries " +
                "(id, transfer_id, wallet_id, direction, amount_paise, balance_before, balance_after) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?)",
                transferId.toString(),
                walletId.toString(),
                direction,
                amountPaise,
                balanceBefore,
                balanceAfter
        );
    }
}

