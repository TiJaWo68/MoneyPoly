package de.in.mp.energy.bill;

import java.math.BigDecimal;

/**
 * Evaluation of the provider's proposed new monthly installment against actual recorded consumption.
 */
public record InstallmentEvaluation(
        BigDecimal billedAnnualizedKwh,
        BigDecimal actualAnnualizedKwh,
        BigDecimal billedNewInstallmentGrossEur,
        BigDecimal recommendedInstallmentGrossEur,
        BigDecimal monthlyDifferenceEur) {
}
