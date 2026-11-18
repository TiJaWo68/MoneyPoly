package de.in.mp.energy.bill;

import java.math.BigDecimal;

/**
 * Result of verifying all line-item and total calculations inside a utility bill.
 */
public record ArithmeticCheckResult(
        boolean apLinesOk,
        boolean gpLinesOk,
        boolean netTotalOk,
        boolean vatTotalOk,
        boolean grossTotalOk,
        boolean balanceDueOk,
        BigDecimal calculatedNetEur,
        BigDecimal maxLineDiffEur) {

    public boolean allOk() {
        return apLinesOk && gpLinesOk && netTotalOk && vatTotalOk && grossTotalOk && balanceDueOk;
    }
}
