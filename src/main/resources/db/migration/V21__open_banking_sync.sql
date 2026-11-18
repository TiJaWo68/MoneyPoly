-- ============================================================================
-- OPEN-BANKING READ-ONLY SYNC (BankSyncProvider)
-- ============================================================================

CREATE TABLE bank_connection (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    provider_id VARCHAR(40) NOT NULL,
    institution_id VARCHAR(120),
    institution_name VARCHAR(120) NOT NULL,
    encrypted_session_payload TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_bank_connection_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'REVOKED', 'ERROR')),
    consent_expires_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bank_connection_household ON bank_connection(household_id);
CREATE INDEX idx_bank_connection_status ON bank_connection(status);

ALTER TABLE finance_account
    ADD COLUMN bank_connection_id UUID REFERENCES bank_connection(id) ON DELETE SET NULL,
    ADD COLUMN external_account_id VARCHAR(120),
    ADD COLUMN auto_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_finance_account_bank_connection ON finance_account(bank_connection_id);
