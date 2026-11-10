package de.in.mp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Balance of an account at a reference date with optional monthly flows,
 * used as input when recording snapshots in bulk.
 */
public record SnapshotValue(LocalDate date, BigDecimal balance, BigDecimal inflow, BigDecimal outflow,
        String notes) {

    public static SnapshotValue ofBalance(LocalDate date, BigDecimal balance) {
        return new SnapshotValue(date, balance, null, null, null);
    }
}
