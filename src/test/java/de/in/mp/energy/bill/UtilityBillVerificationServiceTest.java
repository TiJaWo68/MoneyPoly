package de.in.mp.energy.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.energy.EnergyTariff;
import de.in.mp.energy.EnergyTariffService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.MeterType;
import de.in.mp.importer.PdfTextExtractor;

class UtilityBillVerificationServiceTest {

    private MeterService meterService;
    private MeterReadingService readingService;
    private EnergyTariffService tariffService;
    private UtilityBillVerificationService service;
    private UUID houseId;
    private Meter meter;

    @BeforeEach
    void setUp() {
        meterService = mock(MeterService.class);
        readingService = mock(MeterReadingService.class);
        tariffService = mock(EnergyTariffService.class);
        service = new UtilityBillVerificationService(
                new PdfTextExtractor(),
                List.of(new LogoEnergieBillParser()),
                meterService,
                readingService,
                tariffService);

        houseId = UUID.randomUUID();
        meter = new Meter();
        meter.setId(UUID.randomUUID());
        meter.setHouseId(houseId);
        meter.setName("Strom Gesamt");
        meter.setSerialNumber("MTR-SYNTH-100");
        meter.setType(MeterType.GRID_IMPORT);
        meter.setConversionFactor(BigDecimal.ONE);

        when(meterService.findByHouse(houseId)).thenReturn(List.of(meter));
        when(meterService.requireMeter(meter.getId())).thenReturn(meter);
    }

    @Test
    void reportsPlausibleWhenReadingsMathAndTariffMatchExactly() {
        when(readingService.findByMeter(meter.getId())).thenReturn(List.of(
                reading(LocalDate.of(2025, 11, 30), "10000"),
                reading(LocalDate.of(2026, 5, 31), "11800"),
                reading(LocalDate.of(2026, 11, 30), "13650")));

        EnergyTariff tariff = tariff("TestStrom", "0.2737");
        when(tariffService.findByHouseAndMeterType(houseId, MeterType.GRID_IMPORT)).thenReturn(List.of(tariff));
        when(tariffService.resolveEffectiveUnitPrice(
                houseId, MeterType.GRID_IMPORT, LocalDate.of(2025, 11, 30), LocalDate.of(2026, 11, 30)))
                .thenReturn(new BigDecimal("0.2737"));

        UtilityBill bill = syntheticBill("13650", "1850", "955.50", "95.00");
        UtilityBillVerificationResult result = service.verifyBill(bill, houseId, null);

        assertThat(result.matchedBySerialNumber()).isTrue();
        assertThat(result.verdict()).isEqualTo(BillVerdict.PLAUSIBLE);
        assertThat(result.arithmeticCheck().allOk()).isTrue();
        assertThat(result.tariffComparison().matchesBillGrossOrNet()).isTrue();
        assertThat(result.totalDeltaKwh()).isEqualByComparingTo("0.0");
        assertThat(result.closingReadingDeltaKwh()).isEqualByComparingTo("0.0");
    }

    @Test
    void detectsInflatedProviderEstimateAndOverstatedInstallment() {
        when(readingService.findByMeter(meter.getId())).thenReturn(List.of(
                reading(LocalDate.of(2025, 11, 30), "10000"),
                reading(LocalDate.of(2026, 5, 31), "11800"),
                reading(LocalDate.of(2026, 11, 30), "13150")));

        UtilityBill bill = syntheticBill("13650", "1850", "955.50", "95.00");
        UtilityBillVerificationResult result = service.verifyBill(bill, houseId, null);

        assertThat(result.verdict()).isEqualTo(BillVerdict.DISCREPANCY);
        assertThat(result.totalDeltaKwh()).isEqualByComparingTo("500.0");
        assertThat(result.closingReadingDeltaKwh()).isEqualByComparingTo("500.0");
        assertThat(result.totalDeltaNetEur()).isEqualByComparingTo("115.00");
        assertThat(result.totalDeltaGrossEur()).isEqualByComparingTo("136.85");
        assertThat(result.installmentEvaluation()).isNotNull();
        assertThat(result.installmentEvaluation().recommendedInstallmentGrossEur())
                .isLessThan(result.installmentEvaluation().billedNewInstallmentGrossEur());
    }

    @Test
    void interpolatesBetweenSurroundingReadingsWhenBoundaryDateIsMissing() {
        when(readingService.findByMeter(meter.getId())).thenReturn(List.of(
                reading(LocalDate.of(2025, 11, 30), "10000"),
                reading(LocalDate.of(2026, 6, 10), "11900"),
                reading(LocalDate.of(2026, 11, 30), "13650")));

        UtilityBill bill = syntheticBill("13650", "1850", "955.50", "95.00");
        UtilityBillVerificationResult result = service.verifyBill(bill, houseId, meter.getId());

        assertThat(result.periodComparisons()).hasSize(2);
        assertThat(result.periodComparisons().get(0).matchQuality()).isEqualTo(PeriodMatchQuality.INTERPOLATED);
        assertThat(result.periodComparisons().get(1).matchQuality()).isEqualTo(PeriodMatchQuality.INTERPOLATED);
    }

    @Test
    void flagsArithmeticDiscrepancyWhenTotalDoesNotMatchPriceLines() {
        when(readingService.findByMeter(meter.getId())).thenReturn(List.of(
                reading(LocalDate.of(2025, 11, 30), "10000"),
                reading(LocalDate.of(2026, 5, 31), "11800"),
                reading(LocalDate.of(2026, 11, 30), "13650")));

        UtilityBill brokenMath = syntheticBill("13650", "1850", "999.99", "105.00");
        UtilityBillVerificationResult result = service.verifyBill(brokenMath, houseId, null);

        assertThat(result.arithmeticCheck().allOk()).isFalse();
        assertThat(result.verdict()).isEqualTo(BillVerdict.DISCREPANCY);
    }

    private UtilityBill syntheticBill(
            String finalReading, String secondPeriodKwh, String netTotalEur, String newInstallmentGrossEur) {
        BigDecimal totalKwh = new BigDecimal("1800").add(new BigDecimal(secondPeriodKwh));
        List<UtilityBillPeriod> periods = List.of(
                new UtilityBillPeriod(
                        LocalDate.of(2025, 12, 1), LocalDate.of(2026, 5, 31),
                        new BigDecimal("10000"), new BigDecimal("11800"), new BigDecimal("1800"),
                        UtilityBillReadingSource.METER_READ, "2)"),
                new UtilityBillPeriod(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 11, 30),
                        new BigDecimal("11800"), new BigDecimal(finalReading), new BigDecimal(secondPeriodKwh),
                        UtilityBillReadingSource.ESTIMATED, "3)"));

        BigDecimal ap2 = new BigDecimal(secondPeriodKwh).multiply(new BigDecimal("0.23")).setScale(2);
        List<UtilityBillPriceLine> lines = List.of(
                new UtilityBillPriceLine(
                        LocalDate.of(2025, 12, 1), LocalDate.of(2026, 5, 31),
                        "AP", "LogoStrom", new BigDecimal("0.23000"), false,
                        new BigDecimal("1800"), "kWh", new BigDecimal("19"), new BigDecimal("414.00")),
                new UtilityBillPriceLine(
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 11, 30),
                        "AP", "LogoStrom", new BigDecimal("0.23000"), false,
                        new BigDecimal(secondPeriodKwh), "kWh", new BigDecimal("19"), ap2),
                new UtilityBillPriceLine(
                        LocalDate.of(2025, 12, 1), LocalDate.of(2026, 11, 30),
                        "GP", "LogoStrom", new BigDecimal("116.00"), true,
                        new BigDecimal("365"), "Tage", new BigDecimal("19"), new BigDecimal("116.00")));

        BigDecimal net = new BigDecimal(netTotalEur);
        BigDecimal vat = new BigDecimal("181.55");
        BigDecimal gross = net.add(vat);
        return new UtilityBill(
                "LogoEnergie GmbH",
                "service@example-energy.test",
                "123.456.789-0",
                "LM2026/00012345",
                LocalDate.of(2026, 11, 17),
                LocalDate.of(2025, 12, 1),
                LocalDate.of(2026, 11, 30),
                365,
                "MTR-SYNTH-100",
                "LogoStrom Basis",
                totalKwh,
                net,
                new BigDecimal("19"),
                vat,
                gross,
                new BigDecimal("900.00"),
                gross.subtract(new BigDecimal("900.00")),
                new BigDecimal("88.24"),
                new BigDecimal(newInstallmentGrossEur),
                periods,
                lines);
    }

    private static MeterReading reading(LocalDate date, String value) {
        MeterReading r = new MeterReading();
        r.setReadingDate(date);
        r.setReadingValue(new BigDecimal(value));
        return r;
    }

    private static EnergyTariff tariff(String name, String unitPrice) {
        EnergyTariff t = new EnergyTariff();
        t.setName(name);
        t.setValidFrom(LocalDate.of(2025, 1, 1));
        t.setUnitPriceEur(new BigDecimal(unitPrice));
        return t;
    }
}
