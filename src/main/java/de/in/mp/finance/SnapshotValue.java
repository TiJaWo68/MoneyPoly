package de.in.mp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Balance of an account at a reference date with optional monthly flows and
 * optional invested capital (cost basis for investment accounts), used as input
 * when recording snapshots in bulk.
 */
public record SnapshotValue(LocalDate date, BigDecimal balance, BigDecimal inflow, BigDecimal outflow,
        BigDecimal investedCapital, String notes) {

    public SnapshotValue(LocalDate date, BigDecimal balance, BigDecimal inflow, BigDecimal outflow, String notes) {
        this(date, balance, inflow, outflow, null, notes);
    }

    public static SnapshotValue ofBalance(LocalDate date, BigDecimal balance) {
        return new SnapshotValue(date, balance, null, null, null, null);
    }

    public static SnapshotValue ofBalanceAndInvestedCapital(LocalDate date, BigDecimal balance,
            BigDecimal investedCapital) {
        return new SnapshotValue(date, balance, null, null, investedCapital, null);
    }
}
