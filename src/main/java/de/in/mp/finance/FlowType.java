package de.in.mp.finance;

/**
 * Bookkeeping classification of a transaction or allocation split.
 */
public enum FlowType {

    INCOME,
    EXPENSE,
    EXPENSE_REIMBURSEMENT,
    INTERNAL_TRANSFER,
    SAVINGS_TRANSIT,
    LOAN_DISBURSEMENT,
    LOAN_REPAYMENT
}
