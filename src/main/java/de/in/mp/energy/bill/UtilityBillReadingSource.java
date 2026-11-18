package de.in.mp.energy.bill;

/**
 * Classification of how a meter reading in a utility bill was determined.
 */
public enum UtilityBillReadingSource {
    /** Actual meter reading (e.g. "Abgelesener Wert"). */
    METER_READ,
    /** Estimated or extrapolated reading (e.g. "Geschätzter Wert" or software estimate). */
    ESTIMATED,
    /** Source could not be determined from the bill text. */
    UNKNOWN
}
