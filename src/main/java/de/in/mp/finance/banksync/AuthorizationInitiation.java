package de.in.mp.finance.banksync;

import java.time.OffsetDateTime;

/**
 * Initial redirect/authorization state returned by {@link BankSyncProvider}.
 */
public record AuthorizationInitiation(
        String redirectUrl,
        String externalState,
        String sessionPayload,
        OffsetDateTime consentExpiresAt) {
}
