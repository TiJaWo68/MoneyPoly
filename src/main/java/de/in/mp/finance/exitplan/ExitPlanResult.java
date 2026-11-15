package de.in.mp.finance.exitplan;

import java.math.BigDecimal;
import java.util.List;

/**
 * Complete result of a household exit plan calculation including KPI
 * summary metrics and the full year-by-year projection table.
 */
public record ExitPlanResult(
        ExitPlanMode mode,
        BigDecimal sustainableAnnualLivingBudget,
        BigDecimal averageMonthlyLivingBudget,
        BigDecimal capitalAtFirstPension,
        BigDecimal capitalAtTargetAge,
        Integer exhaustedAtPrimaryAge,
        List<ExitPlanYearRow> rows) {
}
