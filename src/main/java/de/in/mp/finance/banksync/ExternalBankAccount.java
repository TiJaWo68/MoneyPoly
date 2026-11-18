package de.in.mp.finance.banksync;

import de.in.mp.finance.AccountType;

/**
 * Account metadata returned by an Open-Banking provider.
 */
public record ExternalBankAccount(
        String externalAccountId,
        String iban,
        String name,
        String ownerName,
        String currency,
        AccountType suggestedType) {
}
