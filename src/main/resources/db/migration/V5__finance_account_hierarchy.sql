-- ============================================================================
-- FINANCE: HIERARCHICAL ACCOUNT STRUCTURE AND INSTITUTION GROUPS
-- ============================================================================
-- Adds a self-referencing parent link so that institutions (banks, brokers)
-- can group their cash and securities accounts and main accounts can hold
-- sub-accounts (pockets/spaces). The account_type CHECK constraint is extended
-- with INSTITUTION for pure grouping nodes that carry no own balance.

-- 1. Extend the account_type constraint with the INSTITUTION grouping type.
ALTER TABLE finance_account DROP CONSTRAINT chk_finance_account_type;
ALTER TABLE finance_account ADD CONSTRAINT chk_finance_account_type
    CHECK (account_type IN ('CHECKING', 'SAVINGS', 'LOAN', 'INVESTMENT', 'INSTITUTION'));

-- 2. Self-referencing parent link for the hierarchical tree.
ALTER TABLE finance_account
    ADD COLUMN parent_id UUID REFERENCES finance_account(id) ON DELETE SET NULL;

CREATE INDEX idx_finance_account_parent ON finance_account(parent_id);
