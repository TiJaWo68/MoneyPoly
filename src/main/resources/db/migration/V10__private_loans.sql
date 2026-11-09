-- ============================================================================
-- PRIVATE LOANS: MULTI-DEBTOR CONTRACTS, REPAYMENTS AND INTEREST
-- ============================================================================
-- Private loans are not necessarily family-internal and may involve several
-- joint debtors. The single borrower_id column is replaced by the M:N join
-- table loan_contract_person. Repayments are modelled in loan_repayment with
-- separate principal and interest amounts plus a payment method.

-- 1. Contract terms: nominal interest rate and free-text details.
ALTER TABLE loan_contract
    ADD COLUMN annual_interest_rate NUMERIC(8, 4) NOT NULL DEFAULT 0.0000,
    ADD COLUMN notes VARCHAR(255);

-- 2. Multi-debtor join table (joint and several liability).
CREATE TABLE loan_contract_person (
    loan_contract_id UUID NOT NULL REFERENCES loan_contract(id) ON DELETE CASCADE,
    person_id UUID NOT NULL REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT pk_loan_contract_person PRIMARY KEY (loan_contract_id, person_id)
);

-- 3. Migrate the former single debtor into the join table.
INSERT INTO loan_contract_person (loan_contract_id, person_id)
SELECT id, borrower_id FROM loan_contract WHERE borrower_id IS NOT NULL;

ALTER TABLE loan_contract DROP COLUMN borrower_id;

-- 4. Repayment ledger with principal/interest split and payment method.
CREATE TABLE loan_repayment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_contract_id UUID NOT NULL REFERENCES loan_contract(id),
    payer_id UUID NOT NULL REFERENCES person(id),
    repayment_date DATE NOT NULL,
    principal_amount NUMERIC(12, 2) NOT NULL,
    interest_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    payment_method VARCHAR(20) NOT NULL
        CONSTRAINT chk_loan_repayment_method
        CHECK (payment_method IN ('BANK_TRANSFER', 'CASH', 'MANUAL_EXTERNAL')),
    bank_transaction_id UUID REFERENCES bank_transaction(id),
    notes VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_repayment_contract ON loan_repayment(loan_contract_id, repayment_date);
