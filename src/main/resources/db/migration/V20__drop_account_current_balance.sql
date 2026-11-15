-- Drop the denormalized current_balance cache; current balances are derived
-- dynamically from portfolio positions (Mode A) or the latest account snapshot.
ALTER TABLE finance_account DROP COLUMN IF EXISTS current_balance;
