package de.in.mp.energy.bill;

/**
 * Indicates how MoneyPoly meter readings matched a billed period's start/end dates.
 */
public enum PeriodMatchQuality {
    /** Exact readings available in MoneyPoly for both period boundaries. */
    EXACT,
    /** At least one boundary was linearly interpolated between surrounding readings. */
    INTERPOLATED,
    /** Insufficient recorded readings to bracket both boundaries of the period. */
    NO_DATA
}
