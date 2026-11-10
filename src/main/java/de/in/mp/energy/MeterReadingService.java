package de.in.mp.energy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for recording meter readings (individually or as a batch row
 * for a given date) and automatically recalculating consumption deltas across
 * the reading timeline of each meter.
 */
@Service
public class MeterReadingService {

    private static final int DELTA_SCALE = 3;

    private final MeterReadingRepository readingRepository;
    private final MeterRepository meterRepository;

    public MeterReadingService(
            MeterReadingRepository readingRepository,
            MeterRepository meterRepository) {
        this.readingRepository = readingRepository;
        this.meterRepository = meterRepository;
    }

    @Transactional(readOnly = true)
    public List<MeterReading> findByMeter(UUID meterId) {
        return readingRepository.findByMeterIdOrderByReadingDateAsc(meterId);
    }

    @Transactional(readOnly = true)
    public List<MeterReading> findByDate(LocalDate date) {
        return readingRepository.findByReadingDate(date);
    }

    /**
     * Records or updates a single meter reading and recalculates deltas for this
     * meter's timeline.
     */
    @Transactional
    public MeterReading recordReading(
            UUID meterId, LocalDate readingDate, BigDecimal readingValue, String notes) {
        Meter meter = requireMeter(meterId);
        validateInput(readingDate, readingValue);

        List<MeterReading> timeline = new ArrayList<>(
                readingRepository.findByMeterIdOrderByReadingDateAsc(meterId));

        MeterReading target = timeline.stream()
                .filter(existing -> existing.getReadingDate().equals(readingDate))
                .findFirst()
                .orElseGet(() -> {
                    MeterReading created = new MeterReading();
                    created.setMeterId(meterId);
                    created.setReadingDate(readingDate);
                    timeline.add(created);
                    return created;
                });

        target.setReadingValue(readingValue.setScale(DELTA_SCALE, RoundingMode.HALF_UP));
        target.setNotes(notes);

        recalculateTimelineDeltas(meter, timeline);
        return target;
    }

    /**
     * Spreadsheet-style batch entry: records or updates readings for all provided
     * meters at {@code readingDate} in one transaction (skipping {@code null}
     * values) and recalculates deltas.
     */
    @Transactional
    public List<MeterReading> recordBatchReadings(
            LocalDate readingDate, Map<UUID, BigDecimal> valuesByMeterId, String notes) {
        if (readingDate == null) {
            throw new IllegalArgumentException("Reading date is required");
        }
        if (valuesByMeterId == null || valuesByMeterId.isEmpty()) {
            return List.of();
        }

        List<MeterReading> savedReadings = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : valuesByMeterId.entrySet()) {
            BigDecimal value = entry.getValue();
            if (value != null) {
                savedReadings.add(recordReading(entry.getKey(), readingDate, value, notes));
            }
        }
        return savedReadings;
    }

    /**
     * Deletes all readings for a specific date and recalculates deltas for the
     * affected meters.
     */
    @Transactional
    public void deleteReadingsForDate(LocalDate readingDate) {
        List<MeterReading> readingsOnDate = readingRepository.findByReadingDate(readingDate);
        for (MeterReading reading : readingsOnDate) {
            UUID meterId = reading.getMeterId();
            readingRepository.delete(reading);
            Meter meter = requireMeter(meterId);
            List<MeterReading> remaining = new ArrayList<>(
                    readingRepository.findByMeterIdOrderByReadingDateAsc(meterId));
            remaining.removeIf(r -> r.getReadingDate().equals(readingDate));
            recalculateTimelineDeltas(meter, remaining);
        }
    }

    /**
     * Checks whether the entered value is lower than the preceding reading on
     * {@code meterId} without an overflow threshold.
     */
    @Transactional(readOnly = true)
    public Optional<PlausibilityWarning> checkPlausibility(
            UUID meterId, LocalDate readingDate, BigDecimal enteredValue) {
        if (readingDate == null || enteredValue == null) {
            return Optional.empty();
        }
        Meter meter = requireMeter(meterId);
        List<MeterReading> timeline = readingRepository.findByMeterIdOrderByReadingDateAsc(meterId);

        MeterReading previous = null;
        for (MeterReading candidate : timeline) {
            if (candidate.getReadingDate().isBefore(readingDate)) {
                previous = candidate;
            }
        }

        if (previous != null
                && enteredValue.compareTo(previous.getReadingValue()) < 0
                && meter.getOverflowThreshold() == null) {
            return Optional.of(new PlausibilityWarning(
                    meterId,
                    meter.getName(),
                    previous.getReadingDate(),
                    previous.getReadingValue(),
                    enteredValue));
        }
        return Optional.empty();
    }

    /**
     * Computes the consumption delta between a previous reading and a current
     * reading, taking mechanical meter overflow or reset into account.
     */
    static BigDecimal computeDelta(Meter meter, BigDecimal previousValue, BigDecimal currentValue) {
        if (previousValue == null) {
            return null;
        }
        if (currentValue.compareTo(previousValue) >= 0) {
            return currentValue.subtract(previousValue).setScale(DELTA_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal threshold = meter.getOverflowThreshold();
        if (threshold != null && threshold.compareTo(previousValue) >= 0) {
            return threshold.subtract(previousValue)
                    .add(currentValue)
                    .setScale(DELTA_SCALE, RoundingMode.HALF_UP);
        }
        // Meter reset / replacement without overflow threshold: treat current reading as delta from 0
        return currentValue.setScale(DELTA_SCALE, RoundingMode.HALF_UP);
    }

    private void recalculateTimelineDeltas(Meter meter, List<MeterReading> timeline) {
        timeline.sort(Comparator.comparing(MeterReading::getReadingDate));
        BigDecimal previousValue = null;
        for (MeterReading reading : timeline) {
            reading.setConsumptionDelta(computeDelta(meter, previousValue, reading.getReadingValue()));
            readingRepository.save(reading);
            previousValue = reading.getReadingValue();
        }
    }

    private void validateInput(LocalDate readingDate, BigDecimal readingValue) {
        if (readingDate == null) {
            throw new IllegalArgumentException("Reading date is required");
        }
        if (readingValue == null) {
            throw new IllegalArgumentException("Reading value is required");
        }
        if (readingValue.signum() < 0) {
            throw new IllegalArgumentException("Reading value must not be negative");
        }
    }

    private Meter requireMeter(UUID meterId) {
        return meterRepository.findById(meterId)
                .orElseThrow(() -> new IllegalArgumentException("Meter not found: " + meterId));
    }
}

