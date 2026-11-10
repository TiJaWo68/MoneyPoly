package de.in.mp.energy;

import java.math.BigDecimal;

/**
 * Amortization ("Black Zero" / ROI) status for a single {@link EnergyAsset}.
 */
public record AssetAmortizationSummary(
        EnergyAsset asset,
        BigDecimal feedInRevenueEur,
        BigDecimal selfConsumptionSavingsEur,
        BigDecimal wallboxAdvantageEur,
        BigDecimal totalAmortizedEur,
        BigDecimal remainingCostEur,
        BigDecimal amortizedPercent,
        boolean amortized) {
}

