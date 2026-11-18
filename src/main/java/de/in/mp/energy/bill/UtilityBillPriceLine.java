package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;

/**
 * A single energy cost line item (e.g. working price AP or base price GP) from a utility bill.
 */
public record UtilityBillPriceLine(
        LocalDate from,
        LocalDate to,
        String chargeType,
        String description,
        BigDecimal unitPriceNetEur,
        boolean baseFeePerYear,
        BigDecimal quantity,
        String quantityUnit,
        BigDecimal vatPercent,
        BigDecimal netAmountEur) {

    /**
     * Computes the expected net amount in EUR for this line item using standard rounding.
     */
    public BigDecimal expectedNetEur() {
        if (unitPriceNetEur == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        if (baseFeePerYear && from != null) {
            int daysInYear = Year.of(from.getYear()).length();
            return unitPriceNetEur
                    .multiply(quantity)
                    .divide(BigDecimal.valueOf(daysInYear), 2, RoundingMode.HALF_UP);
        }
        return unitPriceNetEur.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }
}
