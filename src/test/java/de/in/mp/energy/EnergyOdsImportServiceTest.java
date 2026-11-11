package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.in.mp.admin.DatabaseResetService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class EnergyOdsImportServiceTest {

    @Test
    void parsesOdsSpreadsheetColumnsDatesTariffsAndMeterValues() {
        EnergyOdsImportService.ParsedOdsData parsed = EnergyOdsImportService.parseOds(createSyntheticOdsStream());

        assertThat(parsed.priceQ1()).isEqualByComparingTo("0.26");
        assertThat(parsed.feedInTariffR1()).isEqualByComparingTo("0.0703");
        assertThat(parsed.gridImportTariffS1()).isEqualByComparingTo("0.34");

        assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.rows().get(0).date()).isEqualTo(LocalDate.of(2024, 1, 4));
        assertThat(parsed.rows().get(0).valuesByColumn().get("Wasser Gesamt"))
                .isEqualByComparingTo("163");
        assertThat(parsed.rows().get(0).valuesByColumn().get("Strom HH"))
                .isEqualByComparingTo("99634");

        EnergyOdsImportService.ParsedRow secondRow = parsed.rows().get(1);
        assertThat(secondRow.date()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(secondRow.valuesByColumn().get("Strom HH")).isEqualByComparingTo("58.5");
        assertThat(secondRow.valuesByColumn().get("PV Dach")).isEqualByComparingTo("1550");

        EnergyOdsImportService.ParsedRow thirdRow = parsed.rows().get(2);
        assertThat(thirdRow.date()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(thirdRow.valuesByColumn().get("PV Dach")).isEqualByComparingTo("2170");
        assertThat(thirdRow.valuesByColumn().get("Wallbox ID4")).isEqualByComparingTo("113");
    }

    @Test
    void convertsMonthlyDeltaSeriesIntoCumulativeReadingsAndPreservesCumulativeSeries() {
        List<BigDecimal> deltas = List.of(BigDecimal.ZERO, new BigDecimal("1550"), new BigDecimal("2170"));
        List<BigDecimal> cumulative = EnergyOdsImportService.accumulateDeltas(deltas);

        assertThat(cumulative).containsExactly(
                new BigDecimal("0.000"),
                new BigDecimal("1550.000"),
                new BigDecimal("3720.000"));
    }

    @Test
    void importOdsCreatesAssetsMetersTariffsAndImportsAllReadingRows() {
        EnergyAssetService assetService = mock(EnergyAssetService.class);
        MeterService meterService = mock(MeterService.class);
        EnergyTariffService tariffService = mock(EnergyTariffService.class);
        MeterReadingService readingService = mock(MeterReadingService.class);
        DatabaseResetService databaseResetService = mock(DatabaseResetService.class);

        List<EnergyAsset> assets = new ArrayList<>();
        when(assetService.findAll()).thenReturn(assets);
        when(assetService.save(any(EnergyAsset.class))).thenAnswer(inv -> {
            EnergyAsset a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });

        List<Meter> meters = new ArrayList<>();
        when(meterService.findAll()).thenReturn(meters);
        when(meterService.save(any(Meter.class))).thenAnswer(inv -> {
            Meter m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(UUID.randomUUID());
            }
            return m;
        });

        when(tariffService.findByMeterType(any())).thenReturn(List.of());
        when(readingService.recordBatchReadings(any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<UUID, BigDecimal> batch = inv.getArgument(1);
            return batch.keySet().stream().map(id -> new MeterReading()).toList();
        });

        EnergyOdsImportService importService = new EnergyOdsImportService(
                assetService, meterService, tariffService, readingService, databaseResetService);

        EnergyOdsImportService.OdsImportResult result = importService.importOds(
                createSyntheticOdsStream(),
                new EnergyOdsImportService.OdsAssetCostDefaults(
                        new BigDecimal("12000.00"),
                        new BigDecimal("1500.00"),
                        new BigDecimal("1200.00")));

        verify(databaseResetService).resetEnergyData();
        assertThat(result.assetsConfigured()).isEqualTo(3);
        assertThat(result.metersConfigured()).isEqualTo(14);
        assertThat(result.tariffsCreated()).isEqualTo(3);
        assertThat(result.readingDatesImported()).isEqualTo(3);
        assertThat(result.totalReadingsSaved()).isEqualTo(11);
    }

    private static InputStream createSyntheticOdsStream() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                  <office:body>
                    <office:spreadsheet>
                      <table:table table:name="Zählerstände ab 2024">
                        <table:table-row>
                          <table:table-cell table:number-columns-repeated="16"/>
                          <table:table-cell office:value-type="float" office:value="0.26"/>
                          <table:table-cell office:value-type="currency" office:value="0.0703"/>
                          <table:table-cell office:value-type="currency" office:value="0.34"/>
                        </table:table-row>
                        <table:table-row/>
                        <table:table-row/>
                        <table:table-row/>
                        <table:table-row/>
                        <table:table-row>
                          <table:table-cell office:value-type="date" office:date-value="2024-01-04"><text:p>04.01.2024</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="163"/>
                          <table:table-cell office:value-type="float" office:value="1563"/>
                          <table:table-cell office:value-type="float" office:value="1405"/>
                          <table:table-cell/>
                          <table:table-cell office:value-type="float" office:value="15881"/>
                          <table:table-cell/>
                          <table:table-cell office:value-type="float" office:value="67425"/>
                          <table:table-cell office:value-type="float" office:value="99634"/>
                        </table:table-row>
                        <table:table-row>
                          <table:table-cell office:value-type="date" office:date-value="2026-05-31"><text:p>31.05.2026</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="361"/>
                          <table:table-cell table:number-columns-repeated="6"/>
                          <table:table-cell office:value-type="float" office:value="58.5"/>
                          <table:table-cell table:number-columns-repeated="5"/>
                          <table:table-cell office:value-type="float" office:value="1550"/>
                        </table:table-row>
                        <table:table-row>
                          <table:table-cell office:value-type="date" office:date-value="2026-10-31"><text:p>31.10.2026</text:p></table:table-cell>
                          <table:table-cell table:number-columns-repeated="13"/>
                          <table:table-cell office:value-type="float" office:value="2170"/>
                          <table:table-cell table:number-columns-repeated="10"/>
                          <table:table-cell office:value-type="float" office:value="113"/>
                        </table:table-row>
                      </table:table>
                    </office:spreadsheet>
                  </office:body>
                </office:document-content>
                """;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("content.xml"));
                zos.write(xml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
