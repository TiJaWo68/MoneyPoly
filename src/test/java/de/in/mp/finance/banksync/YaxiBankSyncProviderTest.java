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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.finance.AccountType;

class YaxiBankSyncProviderTest {

    private HttpClient httpClient;
    private YaxiBankSyncProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        provider = new YaxiBankSyncProvider(httpClient, "https://api.yaxi.tech", "yaxi-api-key");
    }

    @Test
    void reportsInteractiveOnlyAndIncludesTradeRepublicByDefault() {
        YaxiBankSyncProvider unconfigured = new YaxiBankSyncProvider(httpClient, "https://api.yaxi.tech", "");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.supportsBackgroundSync()).isFalse();
        assertThat(unconfigured.listInstitutions("DE"))
                .extracting(BankInstitution::name)
                .contains("Trade Republic", "C24 Bank", "Targobank");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completesInteractiveSessionAndFetchesTradeRepublicData() throws Exception {
        HttpResponse<String> initResponse = mockResponse(200, """
                {
                  "sessionId": "yaxi-sess-1",
                  "redirectUrl": "https://widget.yaxi.tech/start/yaxi-sess-1",
                  "expiresAt": "2026-11-17T01:00:00Z"
                }
                """);
        HttpResponse<String> completeResponse = mockResponse(200, """
                {
                  "sessionToken": "yaxi-tok-1",
                  "expiresAt": "2027-02-15T12:00:00Z",
                  "accounts": [
                    {
                      "id": "tr-cash-1",
                      "iban": "DE55 5001 0517 5407 3249 31",
                      "name": "Trade Republic Verrechnungskonto",
                      "ownerName": "Till",
                      "currency": "EUR",
                      "type": "CHECKING"
                    }
                  ]
                }
                """);
        HttpResponse<String> syncResponse = mockResponse(200, """
                {
                  "asOfDate": "2026-11-16",
                  "balance": 8450.25,
                  "monthlyInflow": 1000.00,
                  "monthlyOutflow": 250.00,
                  "transactions": [
                    {
                      "id": "yaxi-tx-1",
                      "bookingDate": "2026-11-12",
                      "amount": 31.42,
                      "counterpartyName": "Trade Republic Bank GmbH",
                      "counterpartyIban": "",
                      "purpose": "Zinsgutschrift"
                    }
                  ]
                }
                """);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(initResponse, completeResponse, syncResponse);

        AuthorizationInitiation init = provider.initiateAuthorization(
                new BankInstitution("trade-republic-de", "Trade Republic", "TRBKDEBB", "DE"),
                "http://localhost:8080/finance",
                "state-yaxi");
        assertThat(init.redirectUrl()).contains("widget.yaxi.tech");

        AuthorizedSession session = provider.completeAuthorization("ticket-777", init.sessionPayload());
        assertThat(session.accounts()).hasSize(1);
        assertThat(session.accounts().get(0).suggestedType()).isEqualTo(AccountType.CHECKING);

        ExternalAccountSyncData syncData = provider.fetchAccountSyncData(
                session.sessionPayload(), "tr-cash-1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16));
        assertThat(syncData.balance()).isEqualByComparingTo("8450.25");
        assertThat(syncData.transactions()).hasSize(1);
        assertThat(syncData.transactions().get(0).purpose()).isEqualTo("Zinsgutschrift");
    }

    @Test
    @SuppressWarnings("unchecked")
    void throwsWhenBalanceMissingInSyncResponse() throws Exception {
        HttpResponse<String> badResponse = mockResponse(200, "{\"asOfDate\":\"2026-11-16\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(badResponse);

        assertThatThrownBy(() -> provider.fetchAccountSyncData(
                "{\"sessionToken\":\"tok\"}", "acc-1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16)))
                .isInstanceOf(IllegalStateException.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> mockResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
