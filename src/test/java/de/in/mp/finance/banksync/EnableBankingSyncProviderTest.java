package de.in.mp.finance.banksync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

class EnableBankingSyncProviderTest {

    private HttpClient httpClient;
    private EnableBankingSyncProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        provider = new EnableBankingSyncProvider(
                httpClient, "https://api.enablebanking.com", "app-123", "jwt-token", 180);
    }

    @Test
    void supportsBackgroundSyncAndFallsBackToDefaultInstitutionsWhenUnconfigured() {
        EnableBankingSyncProvider unconfigured = new EnableBankingSyncProvider(
                httpClient, "https://api.enablebanking.com", "", "", 180);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.supportsBackgroundSync()).isTrue();
        assertThat(unconfigured.listInstitutions("DE"))
                .extracting(BankInstitution::name)
                .contains("C24 Bank", "Targobank", "Trade Republic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsInstitutionsAndCompletesAuthorizationFlow() throws Exception {
        HttpResponse<String> aspspsResponse = mockResponse(200, """
                {
                  "aspsps": [
                    { "name": "C24 Bank", "bic": "C24BDEFF", "country": "DE" },
                    { "name": "Targobank", "bic": "CMCIDEDD", "country": "DE" }
                  ]
                }
                """);
        HttpResponse<String> authResponse = mockResponse(200, """
                {
                  "url": "https://enablebanking.com/auth/step1",
                  "authorization_id": "auth-1"
                }
                """);
        HttpResponse<String> sessionResponse = mockResponse(200, """
                {
                  "session_id": "sess-999",
                  "access": { "valid_until": "2027-05-15T12:00:00Z" },
                  "accounts": [
                    {
                      "uid": "ext-acc-1",
                      "account_id": { "iban": "DE12 5001 0517 5407 3249 31" },
                      "name": "C24 Smartkonto",
                      "owner_name": "Max Mustermann",
                      "currency": "EUR",
                      "cash_account_type": "CACC"
                    },
                    {
                      "uid": "ext-acc-2",
                      "account_id": { "iban": "DE99 5001 0517 5407 3249 32" },
                      "name": "Tagesgeld Pocket",
                      "currency": "EUR",
                      "cash_account_type": "SVGS"
                    }
                  ]
                }
                """);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(aspspsResponse, authResponse, sessionResponse);

        List<BankInstitution> institutions = provider.listInstitutions("DE");
        assertThat(institutions).hasSize(2);

        AuthorizationInitiation init = provider.initiateAuthorization(
                institutions.get(0), "https://localhost/callback", "state-1");
        assertThat(init.redirectUrl()).isEqualTo("https://enablebanking.com/auth/step1");
        assertThat(init.sessionPayload()).contains("auth-1");

        AuthorizedSession session = provider.completeAuthorization("auth-code-xyz", init.sessionPayload());
        assertThat(session.sessionPayload()).contains("sess-999");
        assertThat(session.accounts()).hasSize(2);
        assertThat(session.accounts().get(0).iban()).isEqualTo("DE12500105175407324931");
        assertThat(session.accounts().get(0).suggestedType()).isEqualTo(AccountType.CHECKING);
        assertThat(session.accounts().get(1).suggestedType()).isEqualTo(AccountType.SAVINGS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchesBalancesAndTransactionsWithDebitCreditSignNormalization() throws Exception {
        HttpResponse<String> balancesResponse = mockResponse(200, """
                {
                  "balances": [
                    {
                      "balance_type": "CLAV",
                      "reference_date": "2026-11-16",
                      "balance_amount": { "amount": "4520.75", "currency": "EUR" }
                    }
                  ]
                }
                """);
        HttpResponse<String> txResponse = mockResponse(200, """
                {
                  "transactions": [
                    {
                      "entry_reference": "tx-1",
                      "booking_date": "2026-11-05",
                      "transaction_amount": { "amount": "3200.00", "currency": "EUR" },
                      "credit_debit_indicator": "CRDT",
                      "debtor": { "name": "Arbeitgeber GmbH" },
                      "debtor_account": { "iban": "DE44 5001 0517 5407 3249 31" },
                      "remittance_information": ["Gehalt November"]
                    },
                    {
                      "entry_reference": "tx-2",
                      "booking_date": "2026-11-08",
                      "transaction_amount": { "amount": "125.50", "currency": "EUR" },
                      "credit_debit_indicator": "DBIT",
                      "creditor": { "name": "REWE" },
                      "creditor_account": { "iban": "DE88 5001 0517 5407 3249 31" },
                      "remittance_information": ["Einkauf", "Filiale 12"]
                    }
                  ]
                }
                """);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(balancesResponse, txResponse);

        ExternalAccountSyncData syncData = provider.fetchAccountSyncData(
                "{\"session_id\":\"sess-999\"}",
                "ext-acc-1",
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 16));

        assertThat(syncData.balance()).isEqualByComparingTo("4520.75");
        assertThat(syncData.asOfDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(syncData.transactions()).hasSize(2);
        assertThat(syncData.transactions().get(0).amount()).isEqualByComparingTo("3200.00");
        assertThat(syncData.transactions().get(1).amount()).isEqualByComparingTo("-125.50");
        assertThat(syncData.transactions().get(1).purpose()).isEqualTo("Einkauf Filiale 12");
    }

    @Test
    @SuppressWarnings("unchecked")
    void throwsOnHttpFailure() throws Exception {
        HttpResponse<String> errorResponse = mockResponse(401, "{\"error\":\"unauthorized\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(errorResponse);

        assertThatThrownBy(() -> provider.fetchAccounts("{\"session_id\":\"sess-999\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void signsValidRs256JwtFromPemPrivateKey() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair keyPair = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        java.security.PrivateKey loaded = EnableBankingSyncProvider.loadPrivateKey(pem, null);
        EnableBankingSyncProvider jwtProvider = new EnableBankingSyncProvider(
                httpClient, "https://api.enablebanking.com", "app-uuid-1", "", loaded, 180);

        assertThat(jwtProvider.isConfigured()).isTrue();
        String jwt = jwtProvider.createSignedJwt();
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        String headerJson = new String(java.util.Base64.getUrlDecoder().decode(parts[0]),
                java.nio.charset.StandardCharsets.UTF_8);
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(headerJson).contains("\"alg\":\"RS256\"").contains("\"kid\":\"app-uuid-1\"");
        assertThat(payloadJson).contains("\"iss\":\"enablebanking.com\"").contains("\"aud\":\"api.enablebanking.com\"");

        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(verifier.verify(java.util.Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> mockResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
