-- Wallet table: stores each user's wallet and current balance (in paise)
-- UNIQUE index on user_id is the race-guard for concurrent get-or-create
CREATE TABLE wallets
(
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id    TEXT        NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_wallets PRIMARY KEY (id),
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0)
);

-- Enforces race-free get-or-create: INSERT ON CONFLICT (user_id) DO NOTHING
CREATE UNIQUE INDEX idx_wallets_user_id ON wallets (user_id);

