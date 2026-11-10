package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnergyAmortizationServiceTest {

    private EnergyAssetRepository assetRepository;
    private MeterRepository meterRepository;
    private MeterReadingRepository readingRepository;
    private EnergyTariffService tariffService;
    private EnergyAmortizationService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(EnergyAssetRepository.class);
        meterRepository = mock(MeterRepository.class);
        readingRepository = mock(MeterReadingRepository.class);
        tariffService = mock(EnergyTariffService.class);
        service = new EnergyAmortizationService(
                assetRepository, meterRepository, readingRepository, tariffService);
    }

    @Test
    void calculatesPvSelfConsumptionFeedInRevenueAndAssetAmortization() {
        // Real numbers inspired by Zählerstände Abrechnungen TBS10.ods (August 2024):
        // PV Dach generation = 2170 kWh, Grid Export delta = 1657 kWh -> Self-consumption = 513 kWh (or 320 kWh direct)
        // Let's test standard formula from Pflichtenheft 3.4:
        // Eigenverbrauch = PV-Erzeugung (2170) - Netzeinspeisung (1657) = 513 kWh
        // Feed-in tariff = 0.0703 EUR/kWh -> Feed-in revenue = 1657 * 0.0703 = 116.49 EUR
        // Grid import price = 0.3400 EUR/kWh -> Self-consumption savings = 513 * 0.3400 = 174.42 EUR
        UUID pvAssetId = UUID.randomUUID();
        EnergyAsset pvAsset = new EnergyAsset();
        pvAsset.setId(pvAssetId);
        pvAsset.setName("PV Dach");
        pvAsset.setInstallationDate(LocalDate.of(2024, 7, 1));
        pvAsset.setInvestmentCost(new BigDecimal("10000.00"));
        pvAsset.setInitialAmortized(new BigDecimal("500.00"));

        UUID pvMeterId = UUID.randomUUID();
        Meter pvMeter = meter(pvMeterId, "PV Dach", MeterType.PV_GENERATION, pvAssetId);

        UUID exportMeterId = UUID.randomUUID();
        Meter exportMeter = meter(exportMeterId, "Einspeisung", MeterType.GRID_EXPORT, pvAssetId);

        UUID importMeterId = UUID.randomUUID();
        Meter importMeter = meter(importMeterId, "Strom Gesamt", MeterType.GRID_IMPORT, null);

        LocalDate d1 = LocalDate.of(2024, 7, 31);
        LocalDate d2 = LocalDate.of(2024, 8, 31);

        MeterReading pvR1 = reading(pvMeterId, d1, "1550", null);
        MeterReading pvR2 = reading(pvMeterId, d2, "3720", "2170");

        MeterReading expR1 = reading(exportMeterId, d1, "1882", null);
        MeterReading expR2 = reading(exportMeterId, d2, "3539", "1657");

        MeterReading impR1 = reading(importMeterId, d1, "18243", null);
        MeterReading impR2 = reading(importMeterId, d2, "18255", "12");

        when(assetRepository.findAllByOrderByInstallationDateAscNameAsc()).thenReturn(List.of(pvAsset));
        when(meterRepository.findAllByOrderByDisplayOrderAscNameAsc())
                .thenReturn(List.of(importMeter, exportMeter, pvMeter));
        when(readingRepository.findAllByOrderByReadingDateAsc())
                .thenReturn(List.of(pvR1, expR1, impR1, pvR2, expR2, impR2));

        when(tariffService.resolveEffectiveUnitPrice(MeterType.GRID_EXPORT, d1, d2))
                .thenReturn(new BigDecimal("0.0703"));
        when(tariffService.resolveEffectiveUnitPrice(MeterType.GRID_IMPORT, d1, d2))
                .thenReturn(new BigDecimal("0.3400"));

        List<EnergyDateRow> rows = service.buildDateRows();
        assertThat(rows).hasSize(2);

        EnergyDateRow augRow = rows.get(1);
        assertThat(augRow.readingDate()).isEqualTo(d2);
        assertThat(augRow.pvGenerationDeltaKwh()).isEqualByComparingTo("2170.000");
        assertThat(augRow.gridExportDeltaKwh()).isEqualByComparingTo("1657.000");
        assertThat(augRow.pvSelfConsumptionKwh()).isEqualByComparingTo("513.000");
        assertThat(augRow.feedInRevenueEur()).isEqualByComparingTo("116.49");
        assertThat(augRow.selfConsumptionSavingsEur()).isEqualByComparingTo("174.42");
        assertThat(augRow.totalPeriodBenefitEur()).isEqualByComparingTo("290.91");

        List<AssetAmortizationSummary> summaries = service.calculateAssetAmortizations();
        assertThat(summaries).hasSize(1);

        AssetAmortizationSummary summary = summaries.get(0);
        // Total amortized = initial (500.00) + period benefit (290.91) = 790.91
        assertThat(summary.totalAmortizedEur()).isEqualByComparingTo("790.91");
        assertThat(summary.remainingCostEur()).isEqualByComparingTo("9209.09");
        assertThat(summary.amortizedPercent()).isEqualByComparingTo("7.9");
    }

    @Test
    void includesCompanyWallboxChargingAdvantageInAmortization() {
        UUID wallboxAssetId = UUID.randomUUID();
        EnergyAsset wallboxAsset = new EnergyAsset();
        wallboxAsset.setId(wallboxAssetId);
        wallboxAsset.setName("Wallbox");
        wallboxAsset.setInstallationDate(LocalDate.of(2024, 1, 1));
        wallboxAsset.setInvestmentCost(new BigDecimal("1500.00"));
        wallboxAsset.setInitialAmortized(BigDecimal.ZERO);

        UUID wbMeterId = UUID.randomUUID();
        Meter wbMeter = meter(wbMeterId, "Wallbox Dienstwagen", MeterType.WALLBOX_COMPANY, wallboxAssetId);

        LocalDate d1 = LocalDate.of(2025, 8, 31);
        LocalDate d2 = LocalDate.of(2025, 9, 30);

        MeterReading wbR1 = reading(wbMeterId, d1, "1000", null);
        MeterReading wbR2 = reading(wbMeterId, d2, "1200", "200");

        when(assetRepository.findAllByOrderByInstallationDateAscNameAsc()).thenReturn(List.of(wallboxAsset));
        when(meterRepository.findAllByOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(wbMeter));
        when(readingRepository.findAllByOrderByReadingDateAsc()).thenReturn(List.of(wbR1, wbR2));

        // Employer reimburses 0.34 EUR/kWh, lost feed-in is 0.0703 EUR/kWh -> net advantage per kWh = 0.2697 EUR
        // 200 kWh * 0.2697 = 53.94 EUR
        when(tariffService.resolveEffectiveUnitPrice(MeterType.WALLBOX_COMPANY, d1, d2))
                .thenReturn(new BigDecimal("0.3400"));
        when(tariffService.resolveEffectiveUnitPrice(MeterType.GRID_EXPORT, d1, d2))
                .thenReturn(new BigDecimal("0.0703"));
        when(tariffService.resolveEffectiveUnitPrice(MeterType.GRID_IMPORT, d1, d2))
                .thenReturn(new BigDecimal("0.2600"));

        List<AssetAmortizationSummary> summaries = service.calculateAssetAmortizations();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).wallboxAdvantageEur()).isEqualByComparingTo("53.94");
        assertThat(summaries.get(0).totalAmortizedEur()).isEqualByComparingTo("53.94");
        assertThat(summaries.get(0).remainingCostEur()).isEqualByComparingTo("1446.06");
    }

    @Test
    void calculatesAverageConsumptionPerMeterOnlyAcrossPeriodsWithActualReadings() {
        UUID waterHvId = UUID.randomUUID();
        Meter waterHv = meter(waterHvId, "Wasser HV", MeterType.WATER, null);

        UUID gasId = UUID.randomUUID();
        Meter gas = meter(gasId, "Gas", MeterType.GAS, null);

        LocalDate d1 = LocalDate.of(2024, 1, 4);
        LocalDate d2 = LocalDate.of(2024, 4, 30);
        LocalDate d3 = LocalDate.of(2024, 6, 1);
        LocalDate d4 = LocalDate.of(2024, 7, 31);
        LocalDate d5 = LocalDate.of(2024, 9, 30);

        // Water HV is only read on d1 (initial, no delta), d4 (delta 26), and d5 (delta 4).
        // Dates d2 and d3 have no Water HV readings and must NOT dilute the average!
        // Expected average for Water HV = (26 + 4) / 2 = 15.000
        MeterReading w1 = reading(waterHvId, d1, "1563", null);
        MeterReading w4 = reading(waterHvId, d4, "1589", "26");
        MeterReading w5 = reading(waterHvId, d5, "1593", "4");

        // Gas is read on d2 (initial), d3 (delta 4), d4 (delta 9), d5 (delta 2) -> avg = (4+9+2)/3 = 5.000
        MeterReading g2 = reading(gasId, d2, "16613", null);
        MeterReading g3 = reading(gasId, d3, "16617", "4");
        MeterReading g4 = reading(gasId, d4, "16626", "9");
        MeterReading g5 = reading(gasId, d5, "16628", "2");

        when(meterRepository.findAllByOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(waterHv, gas));
        when(readingRepository.findAllByOrderByReadingDateAsc())
                .thenReturn(List.of(w1, g2, g3, w4, g4, w5, g5));

        List<EnergyDateRow> rows = service.buildDateRows();
        var averages = service.calculateAverageDeltasByMeter(rows);

        assertThat(averages.get(waterHvId)).isEqualByComparingTo("15.000");
        assertThat(averages.get(gasId)).isEqualByComparingTo("5.000");
    }

    private Meter meter(UUID id, String name, MeterType type, UUID assetId) {
        Meter meter = new Meter();
        meter.setId(id);
        meter.setName(name);
        meter.setType(type);
        meter.setAssetId(assetId);
        return meter;
    }

    private MeterReading reading(UUID meterId, LocalDate date, String value, String delta) {
        MeterReading reading = new MeterReading();
        reading.setId(UUID.randomUUID());
        reading.setMeterId(meterId);
        reading.setReadingDate(date);
        reading.setReadingValue(new BigDecimal(value));
        if (delta != null) {
            reading.setConsumptionDelta(new BigDecimal(delta));
        }
        return reading;
    }
}

