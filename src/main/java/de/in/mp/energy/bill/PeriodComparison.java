package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Comparison between one billed sub-period and MoneyPoly's recorded meter readings.
 */
public record PeriodComparison(
        LocalDate from,
        LocalDate to,
        long days,
        BigDecimal billedStartReading,
        BigDecimal billedEndReading,
        BigDecimal billedConsumptionKwh,
        UtilityBillReadingSource readingSource,
        String sourceNote,
        BigDecimal recordedStartReading,
        BigDecimal recordedEndReading,
        BigDecimal recordedConsumptionKwh,
        BigDecimal deltaKwh,
        BigDecimal deltaNetEur,
        BigDecimal deltaGrossEur,
        PeriodMatchQuality matchQuality,
        boolean startExact,
        boolean endExact) {
}
