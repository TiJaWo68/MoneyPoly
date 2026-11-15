package de.in.mp.finance.exitplan;

import java.math.BigDecimal;

/**
 * Single simulated calendar year within a household exit/runway projection.
 */
public record ExitPlanYearRow(
        int yearOffset,
        int calendarYear,
        int primaryAge,
        Integer secondaryAge,
        boolean inWithdrawalPhase,
        BigDecimal startCapital,
        BigDecimal workingAndPensionIncome,
        BigDecimal healthInsuranceCost,
        BigDecimal livingBudget,
        BigDecimal assetWithdrawal,
        BigDecimal investmentReturn,
        BigDecimal endCapital,
        BigDecimal shortfall) {
}
