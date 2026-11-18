package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Complete verification report for an uploaded utility bill.
 */
public record UtilityBillVerificationResult(
        UtilityBill bill,
        UUID matchedMeterId,
        String matchedMeterName,
        boolean matchedBySerialNumber,
        BillVerdict verdict,
        ArithmeticCheckResult arithmeticCheck,
        TariffComparison tariffComparison,
        InstallmentEvaluation installmentEvaluation,
        List<PeriodComparison> periodComparisons,
        BigDecimal recordedTotalConsumptionKwh,
        BigDecimal comparedBilledConsumptionKwh,
        BigDecimal totalDeltaKwh,
        BigDecimal totalDeltaNetEur,
        BigDecimal totalDeltaGrossEur,
        BigDecimal recordedClosingReading,
        BigDecimal closingReadingDeltaKwh,
        int estimatedPeriodCount) {
}
