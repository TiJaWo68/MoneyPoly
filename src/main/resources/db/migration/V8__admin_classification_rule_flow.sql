-- ============================================================================
-- FINANCE: CLASSIFICATION RULE FLOW AS VARCHAR (JPA @Enumerated(STRING))
-- ============================================================================
-- classification_rule.target_flow is a PostgreSQL named enum (flow_type).
-- Hibernate @Enumerated(EnumType.STRING) binds a VARCHAR parameter, which
-- PostgreSQL rejects for named enums (see V3 for app_role). The column is
-- converted to VARCHAR(30) with a CHECK constraint. transaction_allocation.flow
-- keeps the named enum until that table is mapped as a JPA entity.

ALTER TABLE classification_rule ALTER COLUMN target_flow TYPE VARCHAR(30)
    USING target_flow::text;

ALTER TABLE classification_rule ADD CONSTRAINT chk_classification_rule_flow
    CHECK (target_flow IN (
        'INCOME',
        'EXPENSE',
        'EXPENSE_REIMBURSEMENT',
        'INTERNAL_TRANSFER',
        'SAVINGS_TRANSIT',
        'LOAN_DISBURSEMENT',
        'LOAN_REPAYMENT'
    ));
