-- ============================================================================
-- WEALTH EVENTS (MAJOR EXPENSES & LINKED PRIVATE LOANS FOR HISTORY CHART)
-- ============================================================================

CREATE TABLE wealth_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    event_date DATE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    description VARCHAR(255) NOT NULL,
    loan_contract_id UUID REFERENCES loan_contract(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wealth_event_household_date ON wealth_event(household_id, event_date);
