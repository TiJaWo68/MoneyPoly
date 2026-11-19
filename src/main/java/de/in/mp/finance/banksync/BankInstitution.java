package de.in.mp.finance.banksync;

/**
 * Bank institution offered by an Open-Banking provider.
 */
public record BankInstitution(
        String id,
        String name,
        String bic,
        String country,
        Long maxConsentValiditySeconds) {

    public BankInstitution(String id, String name, String bic, String country) {
        this(id, name, bic, country, null);
    }
}
