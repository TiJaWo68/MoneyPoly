package de.in.mp.energy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Spreadsheet-style row representing all meter readings and calculated PV/ROI
 * metrics for a single reading date (analogous to a row in
 * {@code Zählerstände Abrechnungen TBS10.ods}).
 */
public record EnergyDateRow(
        LocalDate readingDate,
        Map<UUID, MeterReading> readingsByMeter,
        BigDecimal gridImportDeltaKwh,
        BigDecimal gridExportDeltaKwh,
        BigDecimal pvGenerationDeltaKwh,
        BigDecimal pvSelfConsumptionKwh,
        BigDecimal selfConsumptionRatioPercent,
        BigDecimal autarkyRatioPercent,
        BigDecimal feedInRevenueEur,
        BigDecimal selfConsumptionSavingsEur,
        BigDecimal wallboxAdvantageEur,
        BigDecimal totalPeriodBenefitEur,
        String notes) {

    public MeterReading readingFor(UUID meterId) {
        return readingsByMeter.get(meterId);
    }
}

