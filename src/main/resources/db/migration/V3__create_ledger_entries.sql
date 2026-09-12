-- Ledger entries: double-entry bookkeeping audit trail — APPEND ONLY, never updated or deleted
-- Every completed transfer creates exactly 2 entries: one debit, one credit
-- balance_before + balance_after enable point-in-time balance reconstruction
CREATE TABLE ledger_entries
(
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    transfer_id    UUID        NOT NULL,
    wallet_id      UUID        NOT NULL,
    direction      TEXT        NOT NULL,
    amount_paise   BIGINT      NOT NULL,
    balance_before BIGINT      NOT NULL,
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_transfer FOREIGN KEY (transfer_id) REFERENCES transfers (id),
    CONSTRAINT fk_ledger_wallet   FOREIGN KEY (wallet_id)   REFERENCES wallets (id),
    CONSTRAINT chk_ledger_direction CHECK (direction IN ('debit', 'credit')),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount_paise > 0)
);

-- Efficient wallet balance history lookups
CREATE INDEX idx_ledger_wallet   ON ledger_entries (wallet_id,   created_at DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entries (transfer_id);

