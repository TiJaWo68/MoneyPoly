package de.in.mp.loans;

import java.math.BigDecimal;

/**
 * Thrown when a repayment would exceed the remaining balance of a contract.
 */
public class LoanOverpaymentException extends RuntimeException {

    private final BigDecimal overpaymentAmount;

    public LoanOverpaymentException(BigDecimal overpaymentAmount) {
        super("Repayment exceeds the remaining balance by " + overpaymentAmount);
        this.overpaymentAmount = overpaymentAmount;
    }

    public BigDecimal getOverpaymentAmount() {
        return overpaymentAmount;
    }
}
