package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Persistence integration test that executes all Flyway migrations (including
 * {@code V11__energy_module.sql}) against a real PostgreSQL instance and
 * verifies the end-to-end Energy & Meters workflow: asset creation, meter
 * setup with overflow threshold, tariff creation, batch reading entry, and
 * amortization recalculation.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class EnergyPersistenceIntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private EnergyAssetService assetService;

    @Autowired
    private MeterService meterService;

    @Autowired
    private EnergyTariffService tariffService;

    @Autowired
    private MeterReadingService readingService;

    @Autowired
    private EnergyAmortizationService amortizationService;

    @Test
    void persistsAssetsMetersTariffsBatchReadingsAndRecalculatesAmortization() {
        EnergyAsset pvAsset = new EnergyAsset();
        pvAsset.setName("PV Dach IT");
        pvAsset.setInstallationDate(LocalDate.of(2024, 7, 1));
        pvAsset.setInvestmentCost(new BigDecimal("10000.00"));
        pvAsset.setInitialAmortized(new BigDecimal("500.00"));
        EnergyAsset savedAsset = assetService.save(pvAsset);

        Meter pvMeter = new Meter();
        pvMeter.setName("PV Erzeugung IT");
        pvMeter.setType(MeterType.PV_GENERATION);
        pvMeter.setAssetId(savedAsset.getId());
        pvMeter.setDisplayOrder(10);
        Meter savedPvMeter = meterService.save(pvMeter);

        Meter exportMeter = new Meter();
        exportMeter.setName("Einspeisung IT");
        exportMeter.setType(MeterType.GRID_EXPORT);
        exportMeter.setAssetId(savedAsset.getId());
        exportMeter.setDisplayOrder(20);
        Meter savedExportMeter = meterService.save(exportMeter);

        EnergyTariff importTariff = new EnergyTariff();
        importTariff.setName("Strombezug IT");
        importTariff.setForMeterType(MeterType.GRID_IMPORT);
        importTariff.setValidFrom(LocalDate.of(2024, 1, 1));
        importTariff.setUnitPriceEur(new BigDecimal("0.3400"));
        tariffService.save(importTariff);

        EnergyTariff exportTariff = new EnergyTariff();
        exportTariff.setName("EEG IT");
        exportTariff.setForMeterType(MeterType.GRID_EXPORT);
        exportTariff.setValidFrom(LocalDate.of(2024, 1, 1));
        exportTariff.setUnitPriceEur(new BigDecimal("0.0703"));
        tariffService.save(exportTariff);

        readingService.recordBatchReadings(
                LocalDate.of(2024, 7, 31),
                Map.of(
                        savedPvMeter.getId(), new BigDecimal("1000"),
                        savedExportMeter.getId(), new BigDecimal("800")),
                "Initiale Ablesung");

        readingService.recordBatchReadings(
                LocalDate.of(2024, 8, 31),
                Map.of(
                        savedPvMeter.getId(), new BigDecimal("3170"),
                        savedExportMeter.getId(), new BigDecimal("2457")),
                "August Ablesung");

        List<EnergyDateRow> rows = amortizationService.buildDateRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).pvGenerationDeltaKwh()).isEqualByComparingTo("2170.000");
        assertThat(rows.get(1).gridExportDeltaKwh()).isEqualByComparingTo("1657.000");
        assertThat(rows.get(1).pvSelfConsumptionKwh()).isEqualByComparingTo("513.000");

        List<AssetAmortizationSummary> summaries = amortizationService.calculateAssetAmortizations();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).totalAmortizedEur()).isEqualByComparingTo("790.91");
    }
}

