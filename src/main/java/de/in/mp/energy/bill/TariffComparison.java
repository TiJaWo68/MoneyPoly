package de.in.mp.energy.bill;

import java.math.BigDecimal;

/**
 * Comparison of the bill's working/base prices against the tariff configured in MoneyPoly.
 */
public record TariffComparison(
        boolean tariffFoundInApp,
        String appTariffName,
        BigDecimal billWeightedApNetEurPerKwh,
        BigDecimal billWeightedApGrossEurPerKwh,
        BigDecimal appUnitPriceEurPerKwh,
        boolean matchesBillGrossOrNet) {
}
