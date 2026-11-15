package de.in.mp.finance.exitplan;

import java.math.BigDecimal;

/**
 * Input configuration for an annual household exit plan calculation.
 */
public record ExitPlanRequest(
        BigDecimal startingCapital,
        BigDecimal annualReturnPercent,
        ExitPlanMode mode,
        int targetAge,
        BigDecimal targetResidualCapital,
        BigDecimal desiredAnnualBudget,
        int baseCalendarYear,
        ExitPlanPersonInput primaryPerson,
        ExitPlanPersonInput secondaryPerson) {
}
