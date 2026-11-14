package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EcbExchangeRateServiceTest {

    private static final String SAMPLE_ECB_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <Cube>
                <Cube time="2026-11-13">
                  <Cube currency="USD" rate="1.1000"/>
                  <Cube currency="CHF" rate="0.9500"/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    private HttpClient httpClient;
    private EcbExchangeRateService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        service = new EcbExchangeRateService(httpClient, "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml");
    }

    @Test
    void returnsOneForEurWithoutHttpCall() {
        assertThat(service.getRateToEur("EUR")).contains(BigDecimal.ONE);
        assertThat(service.getRateToEur("eur")).contains(BigDecimal.ONE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesDailyXmlAndCachesRatesForTheDay() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SAMPLE_ECB_XML);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Optional<BigDecimal> usdToEur = service.getRateToEur("USD");
        Optional<BigDecimal> chfToEur = service.getRateToEur("CHF");

        assertThat(usdToEur).isPresent();
        // 1 / 1.1000 = 0.909091
        assertThat(usdToEur.get()).isEqualByComparingTo("0.909091");
        assertThat(chfToEur).isPresent();
        // 1 / 0.9500 = 1.052632
        assertThat(chfToEur.get()).isEqualByComparingTo("1.052632");

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToFrankfurterJsonWhenPrimaryXmlFails() throws Exception {
        HttpResponse<String> jsonResponse = mock(HttpResponse.class);
        when(jsonResponse.statusCode()).thenReturn(200);
        when(jsonResponse.body()).thenReturn(
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-13\",\"rates\":{\"USD\":1.1052}}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("PKIX path building failed"))
                .thenReturn(jsonResponse);

        Optional<BigDecimal> usdToEur = service.getRateToEur("USD");

        assertThat(usdToEur).isPresent();
        // 1 / 1.1052 = 0.904814
        assertThat(usdToEur.get()).isEqualByComparingTo("0.904814");
    }
}
