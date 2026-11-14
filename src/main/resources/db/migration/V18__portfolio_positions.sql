-- ============================================================================
-- PORTFOLIO POSITIONS FOR DUAL DEPOT VALUATION (#43)
-- ============================================================================

CREATE TABLE portfolio_position (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES finance_account(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    isin VARCHAR(20),
    ticker VARCHAR(32),
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    quantity NUMERIC(16, 6) NOT NULL,
    buy_in_price NUMERIC(14, 4),
    last_price NUMERIC(14, 4),
    last_price_eur NUMERIC(14, 4),
    fx_rate NUMERIC(14, 6),
    price_updated_at TIMESTAMPTZ,
    manual_price_override BOOLEAN NOT NULL DEFAULT FALSE,
    notes VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_portfolio_position_account ON portfolio_position(account_id);
