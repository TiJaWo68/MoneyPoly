package de.in.mp.finance.exitplan;

import java.math.BigDecimal;

/**
 * Per-person lifecycle, pension and bridge health-insurance parameters for an
 * exit plan simulation.
 */
public record ExitPlanPersonInput(
        String label,
        int currentAge,
        int exitAge,
        int pensionAge,
        BigDecimal monthlyPension,
        BigDecimal monthlySavings,
        BigDecimal monthlyBridgeHealthInsurance,
        boolean familyInsuredWhilePartnerWorks) {

    public static final int DEFAULT_PENSION_AGE = 67;
}
