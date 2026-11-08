-- ============================================================================
-- FINANCE: ACCOUNT MANAGEMENT AND MASTER DATA STRUCTURE
-- ============================================================================
-- Replaces the draft V1 "account" table with the canonical "finance_account"
-- table. The PostgreSQL enum "account_type" is dropped in favor of a VARCHAR
-- column with a CHECK constraint so that the JPA @Enumerated(EnumType.STRING)
-- mapping binds a VARCHAR value (named enums are rejected, see
-- V3__security_role_varchar.sql). The "owner_type" enum is no longer referenced
-- by any table and is removed together with the legacy account table.

-- 1. Detach inbound foreign keys referencing the legacy account table.
ALTER TABLE bank_transaction DROP CONSTRAINT IF EXISTS bank_transaction_account_id_fkey;
ALTER TABLE account_snapshot DROP CONSTRAINT IF EXISTS account_snapshot_account_id_fkey;

-- 2. Remove the legacy account table and its orphaned enum types.
DROP TABLE account;
DROP TYPE account_type;
DROP TYPE owner_type;

-- 3. Create the canonical finance account table.
CREATE TABLE finance_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    account_type VARCHAR(30) NOT NULL
        CONSTRAINT chk_finance_account_type
        CHECK (account_type IN ('CHECKING', 'SAVINGS', 'LOAN', 'INVESTMENT')),
    account_number VARCHAR(50),
    bank_name VARCHAR(100),
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR'
        CONSTRAINT chk_finance_account_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    initial_balance NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    current_balance NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for type-based and active-based lookups.
CREATE INDEX idx_finance_account_type ON finance_account(account_type);
CREATE INDEX idx_finance_account_active ON finance_account(active);

-- 4. Re-anchor the inbound foreign keys to the new table.
ALTER TABLE bank_transaction
    ADD CONSTRAINT bank_transaction_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES finance_account(id);

ALTER TABLE account_snapshot
    ADD CONSTRAINT account_snapshot_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES finance_account(id);
