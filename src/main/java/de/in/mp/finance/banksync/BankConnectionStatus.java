package de.in.mp.finance.banksync;

/**
 * Lifecycle state of a read-only Open-Banking connection.
 */
public enum BankConnectionStatus {

    PENDING,
    ACTIVE,
    EXPIRED,
    REVOKED,
    ERROR
}
