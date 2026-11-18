package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single sub-period from the meter reading / consumption breakdown table of a utility bill.
 */
public record UtilityBillPeriod(
        LocalDate from,
        LocalDate to,
        BigDecimal oldReading,
        BigDecimal newReading,
        BigDecimal consumptionKwh,
        UtilityBillReadingSource source,
        String sourceNote) {
}
