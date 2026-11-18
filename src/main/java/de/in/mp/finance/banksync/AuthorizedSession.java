package de.in.mp.finance.banksync;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Completed Open-Banking AIS session including discovered accounts.
 */
public record AuthorizedSession(
        String sessionPayload,
        OffsetDateTime consentExpiresAt,
        List<ExternalBankAccount> accounts) {
}
