package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YahooMarketPriceProviderTest {

    private HttpClient httpClient;
    private YahooMarketPriceProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        provider = new YahooMarketPriceProvider(httpClient, "https://query1.finance.yahoo.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesValidChartQuote() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "EUNL.DE",
                        "currency": "EUR",
                        "regularMarketPrice": 103.45
                      }
                    }],
                    "error": null
                  }
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Optional<MarketQuote> quote = provider.fetchQuote("EUNL.DE");

        assertThat(quote).isPresent();
        assertThat(quote.get().symbol()).isEqualTo("EUNL.DE");
        assertThat(quote.get().price()).isEqualByComparingTo("103.45");
        assertThat(quote.get().currency()).isEqualTo("EUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesIsinPreferringGermanListing() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "quotes": [
                    { "symbol": "SWDA.L", "exchange": "LSE", "quoteType": "ETF" },
                    { "symbol": "EUNL.DE", "exchange": "GER", "quoteType": "ETF" },
                    { "symbol": "IWDA.AS", "exchange": "AMS", "quoteType": "ETF" }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Optional<String> symbol = provider.resolveTickerByIsin("IE00B4L5Y983");

        assertThat(symbol).contains("EUNL.DE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyOnNetworkFailureOrNon200() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("offline"));

        assertThat(provider.fetchQuote("EUNL.DE")).isEmpty();
        assertThat(provider.resolveTickerByIsin("IE00B4L5Y983")).isEmpty();
    }
}
