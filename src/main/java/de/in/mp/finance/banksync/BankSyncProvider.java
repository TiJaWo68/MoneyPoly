package de.in.mp.finance.banksync;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only (AIS) Open-Banking aggregator abstraction.
 * <p>
 * Implementations never handle or persist user bank passwords/PINs directly and
 * exclusively support account/balance/transaction retrieval (no PIS payment
 * initiation).
 */
public interface BankSyncProvider {

    String providerId();

    String displayName();

    boolean isConfigured();

    boolean supportsBackgroundSync();

    List<BankInstitution> listInstitutions(String country);

    AuthorizationInitiation initiateAuthorization(BankInstitution institution, String callbackUrl, String state);

    AuthorizedSession completeAuthorization(String codeOrTicket, String pendingSessionPayload);

    List<ExternalBankAccount> fetchAccounts(String decryptedSessionPayload);

    ExternalAccountSyncData fetchAccountSyncData(
            String decryptedSessionPayload,
            String externalAccountId,
            LocalDate fromDate,
            LocalDate toDate);
}
