package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Structured representation of a parsed utility provider invoice (e.g. electricity or gas).
 */
public record UtilityBill(
        String providerName,
        String providerEmail,
        String customerNumber,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer billedDays,
        String meterSerialNumber,
        String tariffName,
        BigDecimal totalConsumptionKwh,
        BigDecimal totalNetEur,
        BigDecimal vatRatePercent,
        BigDecimal totalVatEur,
        BigDecimal totalGrossEur,
        BigDecimal prepaymentsGrossEur,
        BigDecimal amountDueGrossEur,
        BigDecimal newInstallmentNetEur,
        BigDecimal newInstallmentGrossEur,
        List<UtilityBillPeriod> periods,
        List<UtilityBillPriceLine> priceLines) {
}
