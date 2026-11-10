package de.in.mp.energy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Plausibility warning returned when a newly entered reading value is lower
 * than the previous reading without a configured overflow threshold.
 */
public record PlausibilityWarning(
        UUID meterId,
        String meterName,
        LocalDate previousDate,
        BigDecimal previousValue,
        BigDecimal enteredValue) {
}

