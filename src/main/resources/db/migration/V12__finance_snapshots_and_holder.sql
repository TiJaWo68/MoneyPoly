-- ============================================================================
-- FINANCE: BALANCE HISTORY WITH MONTHLY FLOWS, ACCOUNT HOLDER & IMPORT KEYS
-- ============================================================================
-- 1. finance_account.account_holder: owner name printed on bank statements,
--    used to match statements of sub-accounts that carry no IBAN.
-- 2. finance_account.import_keys: semicolon-separated statement identifiers
--    that were confirmed once by the user, so that later imports of the same
--    statement type always resolve to the same account.
-- 3. finance_account.display_order: deterministic column order in the
--    balance history matrix.
-- 4. account_snapshot: optional monthly inflow (credits / income), outflow
--    (debits / adjusted expenses, stored as positive magnitude) and notes.
--    Consistency rule: balance = previous balance + inflow - outflow.

ALTER TABLE finance_account
    ADD COLUMN account_holder VARCHAR(100),
    ADD COLUMN import_keys VARCHAR(500),
    ADD COLUMN display_order INT NOT NULL DEFAULT 100;

ALTER TABLE account_snapshot
    ADD COLUMN inflow NUMERIC(12, 2),
    ADD COLUMN outflow NUMERIC(12, 2),
    ADD COLUMN notes VARCHAR(255);

CREATE INDEX idx_account_snapshot_date ON account_snapshot(snapshot_date, account_id);
