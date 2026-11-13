package de.in.mp.energy.vision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import de.in.mp.energy.Meter;

/**
 * Single meter reading candidate extracted by the Vision model from an uploaded
 * photo or energy portal screenshot.
 */
public record ExtractedMeterReading(
        UUID meterId,
        LocalDate readingDate,
        BigDecimal value,
        String unit,
        boolean delta,
        double confidence,
        String sourceHint) {

    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final int VALUE_SCALE = 3;

    /**
     * Converts extracted values to the target meter unit (currently normalizing
     * MWh to kWh when the meter is tracked in kWh).
     */
    public static BigDecimal normalizeToTargetUnit(BigDecimal value, String extractedUnit, String targetUnit) {
        if (value == null) {
            return null;
        }
        String src = extractedUnit == null ? "" : extractedUnit.trim().toLowerCase(Locale.ROOT);
        String dst = targetUnit == null ? "" : targetUnit.trim().toLowerCase(Locale.ROOT);
        if ("mwh".equals(src) && "kwh".equals(dst)) {
            return value.multiply(THOUSAND).setScale(VALUE_SCALE, RoundingMode.HALF_UP);
        }
        if ("kwh".equals(src) && "mwh".equals(dst)) {
            return value.divide(THOUSAND, VALUE_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Normalizes an extracted reading to the meter's unit and rounds electricity
     * (kWh) as well as water (m³/qm) counters to whole integers.
     */
    public static BigDecimal normalizeForMeter(BigDecimal value, String extractedUnit, Meter meter) {
        String targetUnit = meter != null ? meter.getUnit() : "kWh";
        BigDecimal normalized = normalizeToTargetUnit(value, extractedUnit, targetUnit);
        return roundForMeter(normalized, meter, extractedUnit);
    }

    /**
     * Rounds a reading value to whole integers when the meter tracks whole units
     * (electricity in kWh or water in m³/qm).
     */
    public static BigDecimal roundForMeter(BigDecimal value, Meter meter) {
        return roundForMeter(value, meter, null);
    }

    private static BigDecimal roundForMeter(BigDecimal value, Meter meter, String extractedUnit) {
        if (value == null) {
            return null;
        }
        if (requiresWholeUnits(meter, extractedUnit)) {
            return value.setScale(0, RoundingMode.HALF_UP);
        }
        return value;
    }

    public static boolean requiresWholeUnits(Meter meter, String extractedUnit) {
        if (meter != null && meter.getType() != null) {
            return switch (meter.getType()) {
                case WATER, GRID_IMPORT, GRID_EXPORT, PV_GENERATION, WALLBOX_COMPANY, WALLBOX_PRIVATE -> true;
                case GAS -> false;
            };
        }
        String unit = meter != null ? meter.getUnit() : extractedUnit;
        if (unit == null) {
            return false;
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return "kwh".equals(normalized) || "mwh".equals(normalized);
    }
}
