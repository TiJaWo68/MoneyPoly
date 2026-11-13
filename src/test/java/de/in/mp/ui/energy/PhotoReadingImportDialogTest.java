package de.in.mp.ui.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;

import de.in.mp.admin.AppSettingService;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterService;
import de.in.mp.energy.MeterType;
import de.in.mp.energy.vision.ExtractedMeterReading;
import de.in.mp.energy.vision.VisionConfig;
import de.in.mp.energy.vision.VisionMeterClient;
import de.in.mp.energy.vision.VisionPreset;

class PhotoReadingImportDialogTest {

    private UI ui;

    @BeforeEach
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    @Test
    void normalizesUnitsAppliesDeltaToLatestReadingAndPersistsRecognitionHints() {
        MeterService meterService = mock(MeterService.class);
        MeterReadingService readingService = mock(MeterReadingService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        VisionMeterClient visionClient = mock(VisionMeterClient.class);

        Meter pv = new Meter();
        pv.setId(UUID.randomUUID());
        pv.setName("PV Erzeugung");
        pv.setType(MeterType.PV_GENERATION);
        pv.setUnit("kWh");
        pv.setActive(true);

        MeterReading previous = new MeterReading();
        previous.setMeterId(pv.getId());
        previous.setReadingDate(LocalDate.of(2026, 9, 30));
        previous.setReadingValue(new BigDecimal("14000.000"));

        when(meterService.findActive()).thenReturn(List.of(pv));
        when(readingService.findByMeter(pv.getId())).thenReturn(List.of(previous));
        when(appSettingService.getVisionConfig()).thenReturn(
                new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1", "qwen2.5vl:7b", ""));

        LocalDate refDate = LocalDate.of(2026, 10, 31);
        when(visionClient.extractReadings(any(), any(), any(), any(), any(), anyMap())).thenReturn(List.of(
                new ExtractedMeterReading(pv.getId(), refDate, new BigDecimal("1.03"), "MWh", true, 0.92,
                        "Durch PV erzeugt")));

        PhotoReadingImportDialog dialog = new PhotoReadingImportDialog(
                meterService, readingService, appSettingService, visionClient);
        dialog.openForImport(() -> {
        });
        dialog.analyzeBytes("pv.png", "image/png", new byte[] { 1, 2, 3 });

        assertThat(dialog.getRowsForTest()).hasSize(1);
        PhotoReadingImportDialog.ReviewRow row = dialog.getRowsForTest().get(0);
        assertThat(row.value).isEqualTo(new BigDecimal("1030"));
        assertThat(row.addToPrevious).isTrue();
        assertThat(dialog.effectiveValue(row)).isEqualTo(new BigDecimal("15030"));

        dialog.saveAll();

        verify(readingService).recordReading(
                eq(pv.getId()), eq(refDate), eq(new BigDecimal("15030")), eq("Durch PV erzeugt"));
        assertThat(pv.getRecognitionHint()).isEqualTo("Durch PV erzeugt");
        verify(meterService).save(pv);
    }

    @Test
    void acceptsPastedClipboardScreenshotAndRoundsWaterToWholeCubicMeters() {
        MeterService meterService = mock(MeterService.class);
        MeterReadingService readingService = mock(MeterReadingService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        VisionMeterClient visionClient = mock(VisionMeterClient.class);

        Meter water = new Meter();
        water.setId(UUID.randomUUID());
        water.setName("Wasser Gesamt");
        water.setType(MeterType.WATER);
        water.setUnit("m3");
        water.setActive(true);

        when(meterService.findActive()).thenReturn(List.of(water));
        when(readingService.findByMeter(water.getId())).thenReturn(List.of());
        when(appSettingService.getVisionConfig()).thenReturn(
                new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1", "qwen2.5vl:7b", ""));

        LocalDate refDate = LocalDate.of(2026, 11, 13);
        when(visionClient.extractReadings(any(), any(), any(), any(), any(), anyMap())).thenReturn(List.of(
                new ExtractedMeterReading(water.getId(), refDate, new BigDecimal("418.73"), "m³", false, 0.94,
                        "Wasseruhr")));

        PhotoReadingImportDialog dialog = new PhotoReadingImportDialog(
                meterService, readingService, appSettingService, visionClient);
        dialog.openForImport(() -> {
        });

        String base64 = Base64.getEncoder().encodeToString(new byte[] { 9, 8, 7 });
        dialog.handlePastedImage("clipboard.png", "image/png", base64);

        assertThat(dialog.getRowsForTest()).hasSize(1);
        PhotoReadingImportDialog.ReviewRow row = dialog.getRowsForTest().get(0);
        assertThat(row.fileName).isEqualTo("clipboard.png");
        assertThat(row.value).isEqualTo(new BigDecimal("419"));
        assertThat(dialog.effectiveValue(row)).isEqualTo(new BigDecimal("419"));
    }

    @Test
    void guardsAgainstBelowPreviousReadingWhenDateIsAfterHigherReading() {
        MeterService meterService = mock(MeterService.class);
        MeterReadingService readingService = mock(MeterReadingService.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        VisionMeterClient visionClient = mock(VisionMeterClient.class);

        Meter export = new Meter();
        export.setId(UUID.randomUUID());
        export.setName("Strom Einspeisung");
        export.setType(MeterType.GRID_EXPORT);
        export.setUnit("kWh");
        export.setActive(true);

        MeterReading octReading = new MeterReading();
        octReading.setMeterId(export.getId());
        octReading.setReadingDate(LocalDate.of(2026, 10, 31));
        octReading.setReadingValue(new BigDecimal("25964"));

        when(meterService.findActive()).thenReturn(List.of(export));
        when(readingService.findByMeter(export.getId())).thenReturn(List.of(octReading));
        when(appSettingService.getVisionConfig()).thenReturn(
                new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1", "qwen2.5vl:7b", ""));

        LocalDate novDate = LocalDate.of(2026, 11, 13);
        when(visionClient.extractReadings(any(), any(), any(), any(), any(), anyMap())).thenReturn(List.of(
                new ExtractedMeterReading(export.getId(), novDate, new BigDecimal("25716"), "kWh", false, 0.95,
                        "OBIS 2.8.0")));

        PhotoReadingImportDialog dialog = new PhotoReadingImportDialog(
                meterService, readingService, appSettingService, visionClient);
        dialog.openForImport(() -> {
        });
        dialog.analyzeBytes("meter.jpg", "image/jpeg", new byte[] { 1, 2, 3 });

        PhotoReadingImportDialog.ReviewRow row = dialog.getRowsForTest().get(0);
        assertThat(dialog.isBelowPrevious(row)).isTrue();
        assertThat(dialog.requestSave()).isFalse();
        verify(readingService, never()).recordReading(any(), any(), any(), any());

        row.readingDate = LocalDate.of(2026, 10, 15);
        assertThat(dialog.isBelowPrevious(row)).isFalse();
    }
}
