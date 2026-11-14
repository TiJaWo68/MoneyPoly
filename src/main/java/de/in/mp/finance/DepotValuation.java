package de.in.mp.finance;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified valuation result for an {@link AccountType#INVESTMENT} depot account,
 * produced identically by position-based valuation (Mode A) and snapshot-based
 * fallback valuation (Mode B).
 */
public record DepotValuation(
        UUID accountId,
        boolean positionBased,
        BigDecimal currentValue,
        BigDecimal investedCapital,
        BigDecimal valueWithCostBasis,
        boolean costBasisComplete,
        int positionCount) {

    public Optional<InvestmentPerformance> performance() {
        return InvestmentPerformance.of(valueWithCostBasis, investedCapital);
    }
}
