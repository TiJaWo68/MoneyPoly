package de.in.mp.finance.banksync;

/**
 * Bank institution offered by an Open-Banking provider.
 */
public record BankInstitution(String id, String name, String bic, String country) {
}
