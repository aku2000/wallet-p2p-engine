-- Transfers table: every money movement record
-- idempotency_key UNIQUE is the exactly-once guard — committed in the same tx as balance updates
CREATE TABLE transfers
(
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    from_wallet_id   UUID        NOT NULL,
    to_wallet_id     UUID        NOT NULL,
    amount_paise     BIGINT      NOT NULL,
    status           TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    note             TEXT,
    reversed_by      UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_transfers PRIMARY KEY (id),
    CONSTRAINT fk_transfers_from FOREIGN KEY (from_wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_transfers_to   FOREIGN KEY (to_wallet_id)   REFERENCES wallets (id),
    CONSTRAINT fk_transfers_reversed_by FOREIGN KEY (reversed_by) REFERENCES transfers (id),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_transfers_status CHECK (status IN ('pending', 'completed', 'declined', 'reversed'))
);

-- Exactly-once enforcement: constraint violation = idempotent replay
CREATE UNIQUE INDEX idx_transfers_idempotency_key ON transfers (idempotency_key);

-- Efficient queries for transfer history by wallet
CREATE INDEX idx_transfers_from_wallet ON transfers (from_wallet_id, created_at DESC);
CREATE INDEX idx_transfers_to_wallet   ON transfers (to_wallet_id,   created_at DESC);

