-- ============================================================================
-- FINANCE: SAVINGS INTEREST RATES, PROMOTIONAL TERMS & GLOBAL APP SETTINGS
-- ============================================================================
-- 1. finance_account promotional and subsequent interest terms for savings accounts:
--    - promotional_interest_rate: promotional rate in percent (e.g. 3.5000)
--    - promotional_until: expiration date of promotional rate
--    - subsequent_interest_rate: regular rate after promotion in percent (e.g. 1.5000)
-- 2. app_setting: key-value storage for application-wide configurations,
--    seeded with the 2% interest rate warning threshold for savings accounts.

ALTER TABLE finance_account
    ADD COLUMN promotional_interest_rate NUMERIC(8, 4),
    ADD COLUMN promotional_until DATE,
    ADD COLUMN subsequent_interest_rate NUMERIC(8, 4);

CREATE TABLE app_setting (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_setting (setting_key, setting_value, description)
VALUES ('savings_interest_warning_threshold', '2.00', 'Warnschwelle für Tagesgeld-Zinsunterschreitung in Prozent');
