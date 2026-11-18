package de.in.mp.finance.banksync;

/**
 * Result of initiating an Open-Banking authorization flow.
 */
public record ConnectionInitiationResult(BankConnection connection, String redirectUrl) {
}
