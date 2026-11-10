package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeterReadingServiceTest {

    private MeterReadingRepository readingRepository;
    private MeterRepository meterRepository;
    private MeterReadingService service;

    @BeforeEach
    void setUp() {
        readingRepository = mock(MeterReadingRepository.class);
        meterRepository = mock(MeterRepository.class);
        service = new MeterReadingService(readingRepository, meterRepository);
    }

    @Test
    void calculateDeltaReturnsNullForFirstReadingAndDifferenceForSubsequentReading() {
        UUID meterId = UUID.randomUUID();
        Meter meter = meter(meterId, "Strom Gesamt", MeterType.GRID_IMPORT, null);
        List<MeterReading> history = new ArrayList<>();

        when(meterRepository.findById(meterId)).thenReturn(Optional.of(meter));
        when(readingRepository.findByMeterIdOrderByReadingDateAsc(meterId)).thenReturn(history);
        when(readingRepository.save(any(MeterReading.class))).thenAnswer(inv -> {
            MeterReading saved = inv.getArgument(0);
            if (!history.contains(saved)) {
                history.add(saved);
            }
            return saved;
        });

        MeterReading first = service.recordReading(meterId, LocalDate.of(2024, 1, 4), new BigDecimal("15881"), null);
        MeterReading second = service.recordReading(meterId, LocalDate.of(2024, 4, 30), new BigDecimal("17370"), null);

        assertThat(first.getConsumptionDelta()).isNull();
        assertThat(second.getConsumptionDelta()).isEqualByComparingTo("1489.000");
    }

    @Test
    void calculateDeltaHandlesMechanicalFiveDigitOverflowAutomatically() {
        // Real scenario from TBS10.ods: Strom HH jumps from 99634 on 02.05.2026 to 58.5 on 31.05.2026
        // With 100000 overflow threshold: delta = (100000 - 99634) + 58.5 = 366 + 58.5 = 424.5
        UUID meterId = UUID.randomUUID();
        Meter meter = meter(meterId, "Strom HH", MeterType.GRID_IMPORT, new BigDecimal("100000"));
        List<MeterReading> history = new ArrayList<>();

        when(meterRepository.findById(meterId)).thenReturn(Optional.of(meter));
        when(readingRepository.findByMeterIdOrderByReadingDateAsc(meterId)).thenReturn(history);
        when(readingRepository.save(any(MeterReading.class))).thenAnswer(inv -> {
            MeterReading saved = inv.getArgument(0);
            if (!history.contains(saved)) {
                history.add(saved);
            }
            return saved;
        });

        service.recordReading(meterId, LocalDate.of(2026, 5, 2), new BigDecimal("99634"), null);
        MeterReading afterOverflow = service.recordReading(
                meterId, LocalDate.of(2026, 5, 31), new BigDecimal("58.5"), null);

        assertThat(afterOverflow.getConsumptionDelta()).isEqualByComparingTo("424.500");
    }

    @Test
    void recordBatchForDateSavesAllNonNullReadingsAndUpdatesExistingForSameDate() {
        UUID waterId = UUID.randomUUID();
        UUID gasId = UUID.randomUUID();
        Meter water = meter(waterId, "Wasser Gesamt", MeterType.WATER, null);
        Meter gas = meter(gasId, "Gas", MeterType.GAS, null);

        List<MeterReading> waterHistory = new ArrayList<>();
        List<MeterReading> gasHistory = new ArrayList<>();

        when(meterRepository.findById(waterId)).thenReturn(Optional.of(water));
        when(meterRepository.findById(gasId)).thenReturn(Optional.of(gas));
        when(readingRepository.findByMeterIdOrderByReadingDateAsc(waterId)).thenReturn(waterHistory);
        when(readingRepository.findByMeterIdOrderByReadingDateAsc(gasId)).thenReturn(gasHistory);
        when(readingRepository.save(any(MeterReading.class))).thenAnswer(inv -> {
            MeterReading r = inv.getArgument(0);
            List<MeterReading> target = r.getMeterId().equals(waterId) ? waterHistory : gasHistory;
            if (!target.contains(r)) {
                target.add(r);
            }
            return r;
        });

        Map<UUID, BigDecimal> firstRow = new LinkedHashMap<>();
        firstRow.put(waterId, new BigDecimal("200"));
        firstRow.put(gasId, new BigDecimal("16626"));
        service.recordBatchReadings(LocalDate.of(2024, 7, 31), firstRow, "Monatsende Juli");

        Map<UUID, BigDecimal> secondRow = new LinkedHashMap<>();
        secondRow.put(waterId, new BigDecimal("206"));
        secondRow.put(gasId, new BigDecimal("16627"));
        List<MeterReading> savedBatch = service.recordBatchReadings(
                LocalDate.of(2024, 8, 31), secondRow, "Monatsende August");

        assertThat(savedBatch).hasSize(2);
        assertThat(waterHistory.get(1).getConsumptionDelta()).isEqualByComparingTo("6.000");
        assertThat(gasHistory.get(1).getConsumptionDelta()).isEqualByComparingTo("1.000");
    }

    @Test
    void checkPlausibilityDetectsLowerValueWhenNoOverflowThresholdConfigured() {
        UUID meterId = UUID.randomUUID();
        Meter meter = meter(meterId, "Wasser Gesamt", MeterType.WATER, null);
        MeterReading previous = new MeterReading();
        previous.setMeterId(meterId);
        previous.setReadingDate(LocalDate.of(2024, 7, 31));
        previous.setReadingValue(new BigDecimal("200"));

        when(meterRepository.findById(meterId)).thenReturn(Optional.of(meter));
        when(readingRepository.findByMeterIdOrderByReadingDateAsc(meterId)).thenReturn(List.of(previous));

        Optional<PlausibilityWarning> warning = service.checkPlausibility(
                meterId, LocalDate.of(2024, 8, 31), new BigDecimal("195"));

        assertThat(warning).isPresent();
        assertThat(warning.get().previousValue()).isEqualByComparingTo("200");
        assertThat(warning.get().enteredValue()).isEqualByComparingTo("195");
    }

    @Test
    void recordReadingRejectsNegativeValue() {
        UUID meterId = UUID.randomUUID();
        Meter meter = meter(meterId, "Wasser Gesamt", MeterType.WATER, null);
        when(meterRepository.findById(meterId)).thenReturn(Optional.of(meter));

        assertThatThrownBy(() -> service.recordReading(
                meterId, LocalDate.of(2024, 8, 31), new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    private Meter meter(UUID id, String name, MeterType type, BigDecimal overflowThreshold) {
        Meter meter = new Meter();
        meter.setId(id);
        meter.setName(name);
        meter.setType(type);
        meter.setOverflowThreshold(overflowThreshold);
        return meter;
    }
}

