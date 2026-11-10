package de.in.mp.energy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for managing physical and virtual household meters.
 */
@Service
public class MeterService {

    private static final int NAME_MAX_LENGTH = 80;
    private static final String UNIT_KWH = "kWh";
    private static final String UNIT_CUBIC_METER = "m3";

    private final MeterRepository repository;

    public MeterService(MeterRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Meter> findAll() {
        return repository.findAllByOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Meter> findActive() {
        return repository.findByActiveTrueOrderByDisplayOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public Meter requireMeter(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Meter not found: " + id));
    }

    @Transactional
    public Meter save(Meter meter) {
        validate(meter);
        return repository.save(meter);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(requireMeter(id));
    }

    private void validate(Meter meter) {
        if (meter == null) {
            throw new IllegalArgumentException("Meter must not be null");
        }
        if (meter.getName() == null || meter.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmedName = meter.getName().trim();
        if (trimmedName.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        meter.setName(trimmedName);

        if (meter.getType() == null) {
            throw new IllegalArgumentException("Meter type is required");
        }
        if (meter.getUnit() == null || meter.getUnit().isBlank()) {
            meter.setUnit(defaultUnitFor(meter.getType()));
        } else {
            meter.setUnit(meter.getUnit().trim());
        }

        if (meter.getConversionFactor() == null) {
            meter.setConversionFactor(BigDecimal.ONE);
        }
        if (meter.getConversionFactor().signum() <= 0) {
            throw new IllegalArgumentException("Conversion factor must be greater than zero");
        }
        if (meter.getOverflowThreshold() != null && meter.getOverflowThreshold().signum() <= 0) {
            throw new IllegalArgumentException("Overflow threshold must be greater than zero");
        }
    }

    public static String defaultUnitFor(MeterType type) {
        if (type == MeterType.GAS || type == MeterType.WATER) {
            return UNIT_CUBIC_METER;
        }
        return UNIT_KWH;
    }
}

