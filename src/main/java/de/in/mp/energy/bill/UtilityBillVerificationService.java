package de.in.mp.energy.bill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.energy.EnergyTariff;
import de.in.mp.energy.EnergyTariffService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.importer.PdfTextExtractor;

/**
 * Parses uploaded utility invoices (PDF) and verifies consumption, arithmetic,
 * tariff alignment, and proposed monthly installments against MoneyPoly data.
 */
@Service
public class UtilityBillVerificationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
    private static final BigDecimal LINE_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.03");
    private static final BigDecimal DISCREPANCY_KWH_MIN = new BigDecimal("20");
    private static final BigDecimal WARNING_KWH_MIN = new BigDecimal("5");
    private static final BigDecimal DISCREPANCY_RATIO = new BigDecimal("0.05");
    private static final BigDecimal TARIFF_TOLERANCE = new BigDecimal("0.005");
    private static final BigDecimal INSTALLMENT_WARNING_EUR = new BigDecimal("10");

    private final PdfTextExtractor pdfTextExtractor;
    private final List<UtilityBillParser> parsers;
    private final MeterService meterService;
    private final MeterReadingService readingService;
    private final EnergyTariffService tariffService;

    public UtilityBillVerificationService(
            PdfTextExtractor pdfTextExtractor,
            List<UtilityBillParser> parsers,
            MeterService meterService,
            MeterReadingService readingService,
            EnergyTariffService tariffService) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.parsers = parsers;
        this.meterService = meterService;
        this.readingService = readingService;
        this.tariffService = tariffService;
    }

    /**
     * Extracts text from PDF bytes, parses the utility bill, and runs verification.
     */
    @Transactional(readOnly = true)
    public UtilityBillVerificationResult verifyPdf(byte[] pdfBytes, UUID houseId, UUID explicitMeterId) {
        String text = pdfTextExtractor.extract(pdfBytes);
        UtilityBill bill = parseText(text);
        return verifyBill(bill, houseId, explicitMeterId);
    }

    /**
     * Parses raw invoice text using the first supporting {@link UtilityBillParser}.
     */
    public UtilityBill parseText(String extractedText) {
        return parsers.stream()
                .filter(p -> p.supports(extractedText))
                .findFirst()
                .map(p -> p.parse(extractedText))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported utility bill PDF format"));
    }

    /**
     * Finds candidate meters matching the bill's house scope and serial number.
     */
    @Transactional(readOnly = true)
    public Optional<Meter> findMatchingMeter(UtilityBill bill, UUID houseId) {
        List<Meter> candidates = houseId != null ? meterService.findByHouse(houseId) : meterService.findAll();
        return findBySerial(candidates, bill != null ? bill.meterSerialNumber() : null);
    }

    /**
     * Verifies a parsed {@link UtilityBill} against recorded meter readings and tariffs.
     */
    @Transactional(readOnly = true)
    public UtilityBillVerificationResult verifyBill(UtilityBill bill, UUID houseId, UUID explicitMeterId) {
        List<Meter> houseMeters = houseId != null ? meterService.findByHouse(houseId) : meterService.findAll();

        Meter targetMeter = null;
        boolean matchedBySerial = false;
        if (explicitMeterId != null) {
            targetMeter = meterService.requireMeter(explicitMeterId);
            matchedBySerial = isSerialMatch(targetMeter, bill.meterSerialNumber());
        } else {
            Optional<Meter> serialMatch = findBySerial(houseMeters, bill.meterSerialNumber());
            if (serialMatch.isPresent()) {
                targetMeter = serialMatch.get();
                matchedBySerial = true;
            }
        }

        List<MeterReading> readings = targetMeter != null
                ? readingService.findByMeter(targetMeter.getId()).stream()
                        .sorted(Comparator.comparing(MeterReading::getReadingDate))
                        .toList()
                : List.of();
        BigDecimal conversionFactor = targetMeter != null && targetMeter.getConversionFactor() != null
                ? targetMeter.getConversionFactor()
                : BigDecimal.ONE;

        BigDecimal weightedApNet = computeWeightedApNet(bill);
        BigDecimal vatFactor = bill.vatRatePercent() != null
                ? BigDecimal.ONE.add(bill.vatRatePercent().divide(HUNDRED, 6, RoundingMode.HALF_UP))
                : BigDecimal.ONE;

        List<PeriodComparison> comparisons = comparePeriods(
                bill, readings, conversionFactor, weightedApNet, vatFactor);

        BigDecimal recordedTotal = null;
        BigDecimal comparedBilledTotal = null;
        BigDecimal totalDeltaKwh = null;
        BigDecimal totalDeltaNet = null;
        BigDecimal totalDeltaGross = null;
        int estimatedCount = 0;

        for (PeriodComparison comp : comparisons) {
            if (comp.readingSource() == UtilityBillReadingSource.ESTIMATED) {
                estimatedCount++;
            }
            if (comp.matchQuality() != PeriodMatchQuality.NO_DATA) {
                recordedTotal = addNullable(recordedTotal, comp.recordedConsumptionKwh());
                comparedBilledTotal = addNullable(comparedBilledTotal, comp.billedConsumptionKwh());
                totalDeltaKwh = addNullable(totalDeltaKwh, comp.deltaKwh());
                totalDeltaNet = addNullable(totalDeltaNet, comp.deltaNetEur());
                totalDeltaGross = addNullable(totalDeltaGross, comp.deltaGrossEur());
            }
        }

        BigDecimal recordedClosing = null;
        BigDecimal closingDeltaKwh = null;
        if (!bill.periods().isEmpty() && !readings.isEmpty()) {
            UtilityBillPeriod lastPeriod = bill.periods().get(bill.periods().size() - 1);
            LookupReading closingLookup = lookupReading(readings, lastPeriod.to(), false);
            if (closingLookup != null) {
                recordedClosing = closingLookup.value();
                if (lastPeriod.newReading() != null) {
                    closingDeltaKwh = lastPeriod.newReading()
                            .subtract(recordedClosing)
                            .setScale(1, RoundingMode.HALF_UP);
                }
            }
        }

        ArithmeticCheckResult arithmetic = checkArithmetic(bill);
        TariffComparison tariffComparison = compareTariff(bill, targetMeter, weightedApNet, vatFactor);
        InstallmentEvaluation installmentEval = evaluateInstallment(bill, totalDeltaKwh, weightedApNet, vatFactor);

        BillVerdict verdict = determineVerdict(
                targetMeter, comparisons, totalDeltaKwh, comparedBilledTotal,
                closingDeltaKwh, arithmetic, installmentEval, estimatedCount);

        return new UtilityBillVerificationResult(
                bill,
                targetMeter != null ? targetMeter.getId() : null,
                targetMeter != null ? targetMeter.getName() : null,
                matchedBySerial,
                verdict,
                arithmetic,
                tariffComparison,
                installmentEval,
                List.copyOf(comparisons),
                recordedTotal,
                comparedBilledTotal,
                totalDeltaKwh,
                totalDeltaNet,
                totalDeltaGross,
                recordedClosing,
                closingDeltaKwh,
                estimatedCount);
    }

    private List<PeriodComparison> comparePeriods(
            UtilityBill bill,
            List<MeterReading> readings,
            BigDecimal conversionFactor,
            BigDecimal weightedApNet,
            BigDecimal vatFactor) {
        List<PeriodComparison> result = new ArrayList<>();
        for (UtilityBillPeriod period : bill.periods()) {
            long days = (period.from() != null && period.to() != null)
                    ? ChronoUnit.DAYS.between(period.from(), period.to()) + 1
                    : 0;
            LookupReading start = !readings.isEmpty() ? lookupPeriodStart(readings, period.from()) : null;
            LookupReading end = !readings.isEmpty() ? lookupReading(readings, period.to(), false) : null;

            BigDecimal recStart = start != null ? start.value() : null;
            BigDecimal recEnd = end != null ? end.value() : null;
            BigDecimal recConsumption = null;
            BigDecimal deltaKwh = null;
            BigDecimal deltaNet = null;
            BigDecimal deltaGross = null;
            PeriodMatchQuality quality = PeriodMatchQuality.NO_DATA;

            if (recStart != null && recEnd != null) {
                recConsumption = recEnd.subtract(recStart)
                        .multiply(conversionFactor)
                        .setScale(1, RoundingMode.HALF_UP);
                if (period.consumptionKwh() != null) {
                    deltaKwh = period.consumptionKwh().subtract(recConsumption).setScale(1, RoundingMode.HALF_UP);
                    BigDecimal periodApNet = resolveApPriceForPeriod(bill, period.from(), period.to(), weightedApNet);
                    if (periodApNet != null) {
                        deltaNet = deltaKwh.multiply(periodApNet).setScale(2, RoundingMode.HALF_UP);
                        deltaGross = deltaNet.multiply(vatFactor).setScale(2, RoundingMode.HALF_UP);
                    }
                }
                quality = (start.exact() && end.exact())
                        ? PeriodMatchQuality.EXACT
                        : PeriodMatchQuality.INTERPOLATED;
            }

            result.add(new PeriodComparison(
                    period.from(),
                    period.to(),
                    days,
                    period.oldReading(),
                    period.newReading(),
                    period.consumptionKwh(),
                    period.source(),
                    period.sourceNote(),
                    recStart,
                    recEnd,
                    recConsumption,
                    deltaKwh,
                    deltaNet,
                    deltaGross,
                    quality,
                    start != null && start.exact(),
                    end != null && end.exact()));
        }
        return result;
    }

    private LookupReading lookupPeriodStart(List<MeterReading> readings, LocalDate from) {
        if (from == null) {
            return null;
        }
        LocalDate eveningBefore = from.minusDays(1);
        LookupReading exactBefore = findExact(readings, eveningBefore);
        if (exactBefore != null) {
            return exactBefore;
        }
        LookupReading exactStart = findExact(readings, from);
        if (exactStart != null) {
            return exactStart;
        }
        LookupReading interpolatedBefore = interpolateReading(readings, eveningBefore);
        if (interpolatedBefore != null) {
            return interpolatedBefore;
        }
        return interpolateReading(readings, from);
    }

    private LookupReading lookupReading(List<MeterReading> readings, LocalDate targetDate, boolean tryPreviousDay) {
        if (targetDate == null) {
            return null;
        }
        if (tryPreviousDay) {
            LookupReading exactBefore = findExact(readings, targetDate.minusDays(1));
            if (exactBefore != null) {
                return exactBefore;
            }
        }
        LookupReading exact = findExact(readings, targetDate);
        if (exact != null) {
            return exact;
        }
        return interpolateReading(readings, targetDate);
    }

    private LookupReading findExact(List<MeterReading> readings, LocalDate date) {
        for (MeterReading r : readings) {
            if (Objects.equals(r.getReadingDate(), date) && r.getReadingValue() != null) {
                return new LookupReading(r.getReadingValue().setScale(1, RoundingMode.HALF_UP), true);
            }
        }
        return null;
    }

    private LookupReading interpolateReading(List<MeterReading> readings, LocalDate targetDate) {
        MeterReading before = null;
        MeterReading after = null;
        for (MeterReading r : readings) {
            if (r.getReadingDate() == null || r.getReadingValue() == null) {
                continue;
            }
            if (!r.getReadingDate().isAfter(targetDate)) {
                before = r;
            } else {
                after = r;
                break;
            }
        }
        if (before == null || after == null) {
            return null;
        }
        long totalDays = ChronoUnit.DAYS.between(before.getReadingDate(), after.getReadingDate());
        long targetDays = ChronoUnit.DAYS.between(before.getReadingDate(), targetDate);
        if (totalDays <= 0) {
            return new LookupReading(before.getReadingValue().setScale(1, RoundingMode.HALF_UP), true);
        }
        BigDecimal ratio = BigDecimal.valueOf(targetDays)
                .divide(BigDecimal.valueOf(totalDays), 8, RoundingMode.HALF_UP);
        BigDecimal interpolated = before.getReadingValue()
                .add(after.getReadingValue().subtract(before.getReadingValue()).multiply(ratio))
                .setScale(1, RoundingMode.HALF_UP);
        return new LookupReading(interpolated, false);
    }

    private ArithmeticCheckResult checkArithmetic(UtilityBill bill) {
        boolean apOk = true;
        boolean gpOk = true;
        BigDecimal maxDiff = BigDecimal.ZERO;
        BigDecimal calculatedNet = BigDecimal.ZERO;

        for (UtilityBillPriceLine line : bill.priceLines()) {
            if (line.netAmountEur() == null) {
                continue;
            }
            calculatedNet = calculatedNet.add(line.netAmountEur());
            BigDecimal diff = line.netAmountEur().subtract(line.expectedNetEur()).abs();
            if (diff.compareTo(maxDiff) > 0) {
                maxDiff = diff;
            }
            if (diff.compareTo(LINE_TOLERANCE) > 0) {
                if ("AP".equals(line.chargeType())) {
                    apOk = false;
                } else if ("GP".equals(line.chargeType())) {
                    gpOk = false;
                }
            }
        }

        boolean netOk = bill.priceLines().isEmpty()
                || bill.totalNetEur() == null
                || bill.totalNetEur().subtract(calculatedNet).abs().compareTo(TOTAL_TOLERANCE) <= 0;

        boolean vatOk = bill.totalNetEur() == null
                || bill.vatRatePercent() == null
                || bill.totalVatEur() == null
                || bill.totalNetEur()
                        .multiply(bill.vatRatePercent())
                        .divide(HUNDRED, 2, RoundingMode.HALF_UP)
                        .subtract(bill.totalVatEur())
                        .abs()
                        .compareTo(TOTAL_TOLERANCE) <= 0;

        boolean grossOk = bill.totalNetEur() == null
                || bill.totalVatEur() == null
                || bill.totalGrossEur() == null
                || bill.totalNetEur().add(bill.totalVatEur())
                        .subtract(bill.totalGrossEur())
                        .abs()
                        .compareTo(TOTAL_TOLERANCE) <= 0;

        boolean balanceOk = bill.totalGrossEur() == null
                || bill.amountDueGrossEur() == null
                || bill.totalGrossEur()
                        .subtract(bill.prepaymentsGrossEur() != null ? bill.prepaymentsGrossEur() : BigDecimal.ZERO)
                        .subtract(bill.amountDueGrossEur())
                        .abs()
                        .compareTo(TOTAL_TOLERANCE) <= 0;

        return new ArithmeticCheckResult(apOk, gpOk, netOk, vatOk, grossOk, balanceOk, calculatedNet, maxDiff);
    }

    private TariffComparison compareTariff(
            UtilityBill bill, Meter targetMeter, BigDecimal weightedApNet, BigDecimal vatFactor) {
        BigDecimal weightedApGross = weightedApNet != null
                ? weightedApNet.multiply(vatFactor).setScale(4, RoundingMode.HALF_UP)
                : null;
        if (targetMeter == null || tariffService == null) {
            return new TariffComparison(false, null, weightedApNet, weightedApGross, null, false);
        }

        List<EnergyTariff> tariffs = tariffService.findByHouseAndMeterType(
                targetMeter.getHouseId(), targetMeter.getType());
        if (tariffs.isEmpty()) {
            return new TariffComparison(false, null, weightedApNet, weightedApGross, null, false);
        }

        BigDecimal appPrice = tariffService.resolveEffectiveUnitPrice(
                targetMeter.getHouseId(),
                targetMeter.getType(),
                bill.periodStart() != null ? bill.periodStart().minusDays(1) : bill.invoiceDate(),
                bill.periodEnd() != null ? bill.periodEnd() : bill.invoiceDate());

        String tariffName = tariffs.stream()
                .filter(t -> bill.periodEnd() == null
                        || (!t.getValidFrom().isAfter(bill.periodEnd())
                                && (t.getValidTo() == null || !t.getValidTo().isBefore(bill.periodEnd()))))
                .map(EnergyTariff::getName)
                .findFirst()
                .orElse(tariffs.get(tariffs.size() - 1).getName());

        boolean matches = appPrice != null
                && appPrice.signum() > 0
                && ((weightedApGross != null && appPrice.subtract(weightedApGross).abs().compareTo(TARIFF_TOLERANCE) <= 0)
                        || (weightedApNet != null && appPrice.subtract(weightedApNet).abs().compareTo(TARIFF_TOLERANCE) <= 0));

        return new TariffComparison(true, tariffName, weightedApNet, weightedApGross, appPrice, matches);
    }

    private InstallmentEvaluation evaluateInstallment(
            UtilityBill bill, BigDecimal totalDeltaKwh, BigDecimal weightedApNet, BigDecimal vatFactor) {
        if (bill.newInstallmentGrossEur() == null
                || bill.billedDays() == null
                || bill.billedDays() <= 0
                || bill.totalConsumptionKwh() == null) {
            return null;
        }
        BigDecimal billedDaysBd = BigDecimal.valueOf(bill.billedDays());
        BigDecimal billedAnnualKwh = bill.totalConsumptionKwh()
                .multiply(DAYS_PER_YEAR)
                .divide(billedDaysBd, 0, RoundingMode.HALF_UP);

        BigDecimal actualPeriodKwh = totalDeltaKwh != null
                ? bill.totalConsumptionKwh().subtract(totalDeltaKwh).max(BigDecimal.ZERO)
                : bill.totalConsumptionKwh();
        BigDecimal actualAnnualKwh = actualPeriodKwh
                .multiply(DAYS_PER_YEAR)
                .divide(billedDaysBd, 0, RoundingMode.HALF_UP);

        BigDecimal totalGpNet = bill.priceLines().stream()
                .filter(l -> "GP".equals(l.chargeType()) && l.netAmountEur() != null)
                .map(UtilityBillPriceLine::netAmountEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal annualGpNet = totalGpNet.multiply(DAYS_PER_YEAR).divide(billedDaysBd, 2, RoundingMode.HALF_UP);

        BigDecimal apNet = weightedApNet != null ? weightedApNet : BigDecimal.ZERO;
        BigDecimal estimatedAnnualGross = actualAnnualKwh.multiply(apNet)
                .add(annualGpNet)
                .multiply(vatFactor);
        BigDecimal recommendedMonthly = estimatedAnnualGross.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        BigDecimal monthlyDiff = bill.newInstallmentGrossEur().subtract(recommendedMonthly);

        return new InstallmentEvaluation(
                billedAnnualKwh,
                actualAnnualKwh,
                bill.newInstallmentGrossEur(),
                recommendedMonthly,
                monthlyDiff);
    }

    private BillVerdict determineVerdict(
            Meter targetMeter,
            List<PeriodComparison> comparisons,
            BigDecimal totalDeltaKwh,
            BigDecimal comparedBilledTotal,
            BigDecimal closingDeltaKwh,
            ArithmeticCheckResult arithmetic,
            InstallmentEvaluation installmentEval,
            int estimatedCount) {
        if (!arithmetic.allOk()) {
            return BillVerdict.DISCREPANCY;
        }
        BigDecimal threshold = DISCREPANCY_KWH_MIN;
        if (comparedBilledTotal != null) {
            threshold = threshold.max(comparedBilledTotal.multiply(DISCREPANCY_RATIO));
        }
        if (totalDeltaKwh != null && totalDeltaKwh.abs().compareTo(threshold) > 0) {
            return BillVerdict.DISCREPANCY;
        }
        if (closingDeltaKwh != null && closingDeltaKwh.abs().compareTo(DISCREPANCY_KWH_MIN) > 0) {
            return BillVerdict.DISCREPANCY;
        }
        for (PeriodComparison comp : comparisons) {
            if (comp.deltaKwh() != null && comp.deltaKwh().abs().compareTo(DISCREPANCY_KWH_MIN) > 0) {
                return BillVerdict.DISCREPANCY;
            }
        }

        boolean anyCompared = comparisons.stream().anyMatch(c -> c.matchQuality() != PeriodMatchQuality.NO_DATA);
        boolean unverifiedEstimate = comparisons.stream()
                .anyMatch(c -> c.readingSource() == UtilityBillReadingSource.ESTIMATED
                        && c.matchQuality() == PeriodMatchQuality.NO_DATA);

        if (targetMeter == null || !anyCompared || unverifiedEstimate) {
            return BillVerdict.WARNING;
        }
        if (totalDeltaKwh != null && totalDeltaKwh.abs().compareTo(WARNING_KWH_MIN) > 0) {
            return BillVerdict.WARNING;
        }
        if (closingDeltaKwh != null && closingDeltaKwh.abs().compareTo(WARNING_KWH_MIN) > 0) {
            return BillVerdict.WARNING;
        }
        if (installmentEval != null && installmentEval.monthlyDifferenceEur().abs().compareTo(INSTALLMENT_WARNING_EUR) > 0) {
            return BillVerdict.WARNING;
        }
        return estimatedCount > 0 && !anyCompared ? BillVerdict.WARNING : BillVerdict.PLAUSIBLE;
    }

    private BigDecimal computeWeightedApNet(UtilityBill bill) {
        BigDecimal totalApNet = BigDecimal.ZERO;
        BigDecimal totalApKwh = BigDecimal.ZERO;
        for (UtilityBillPriceLine line : bill.priceLines()) {
            if ("AP".equals(line.chargeType()) && line.netAmountEur() != null && line.quantity() != null) {
                totalApNet = totalApNet.add(line.netAmountEur());
                totalApKwh = totalApKwh.add(line.quantity());
            }
        }
        if (totalApKwh.signum() > 0) {
            return totalApNet.divide(totalApKwh, 5, RoundingMode.HALF_UP);
        }
        if (bill.totalNetEur() != null && bill.totalConsumptionKwh() != null && bill.totalConsumptionKwh().signum() > 0) {
            return bill.totalNetEur().divide(bill.totalConsumptionKwh(), 5, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal resolveApPriceForPeriod(
            UtilityBill bill, LocalDate from, LocalDate to, BigDecimal fallback) {
        if (from != null && to != null) {
            for (UtilityBillPriceLine line : bill.priceLines()) {
                if ("AP".equals(line.chargeType())
                        && Objects.equals(line.from(), from)
                        && Objects.equals(line.to(), to)
                        && line.unitPriceNetEur() != null) {
                    return line.unitPriceNetEur();
                }
            }
        }
        return fallback;
    }

    private Optional<Meter> findBySerial(List<Meter> candidates, String billSerial) {
        String normalizedBill = normalizeSerial(billSerial);
        if (normalizedBill == null) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(m -> normalizedBill.equals(normalizeSerial(m.getSerialNumber())))
                .findFirst();
    }

    private boolean isSerialMatch(Meter meter, String billSerial) {
        String a = normalizeSerial(meter != null ? meter.getSerialNumber() : null);
        String b = normalizeSerial(billSerial);
        return a != null && a.equals(b);
    }

    private String normalizeSerial(String serial) {
        if (serial == null || serial.isBlank()) {
            return null;
        }
        return serial.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
    }

    private BigDecimal addNullable(BigDecimal a, BigDecimal b) {
        if (b == null) {
            return a;
        }
        return a == null ? b : a.add(b);
    }

    private record LookupReading(BigDecimal value, boolean exact) {
    }
}
