package de.in.mp.finance;

import java.math.BigDecimal;

/**
 * Aggregated key performance indicators of the financial dashboard.
 */
public record DashboardKpis(
        BigDecimal netWorth,
        BigDecimal liquidity,
        BigDecimal investedAssets,
        BigDecimal loanReceivables) {
}
