package de.in.mp.finance;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fetches and caches daily EUR foreign exchange reference rates from the
 * European Central Bank (ECB). Provides conversion factors from foreign
 * trading currencies (e.g. USD, CHF, GBP) to EUR.
 */
@Service
public class EcbExchangeRateService {

    private static final Logger LOG = LoggerFactory.getLogger(EcbExchangeRateService.class);

    private static final String DEFAULT_ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final String DEFAULT_FRANKFURTER_URL = "https://api.frankfurter.dev/v1/latest?base=EUR";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int HTTP_OK = 200;
    private static final int FX_SCALE = 6;

    private final HttpClient httpClient;
    private final String ecbUrl;
    private final String frankfurterUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private LocalDate cachedDate;
    private Map<String, BigDecimal> cachedRatesToEur = Map.of();

    @Autowired
    public EcbExchangeRateService(
            @Value("${moneypoly.market-data.ecb-url:" + DEFAULT_ECB_URL + "}") String ecbUrl) {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), ecbUrl, DEFAULT_FRANKFURTER_URL);
    }

    EcbExchangeRateService(HttpClient httpClient, String ecbUrl) {
        this(httpClient, ecbUrl, DEFAULT_FRANKFURTER_URL);
    }

    EcbExchangeRateService(HttpClient httpClient, String ecbUrl, String frankfurterUrl) {
        this.httpClient = httpClient;
        this.ecbUrl = ecbUrl != null ? ecbUrl : DEFAULT_ECB_URL;
        this.frankfurterUrl = frankfurterUrl != null ? frankfurterUrl : DEFAULT_FRANKFURTER_URL;
    }

    /**
     * Returns the factor {@code rateToEur} such that {@code amountEur = amountInCurrency * rateToEur}.
     */
    public synchronized Optional<BigDecimal> getRateToEur(String currency) {
        if (currency == null || currency.isBlank()) {
            return Optional.empty();
        }
        String normalized = currency.trim().toUpperCase();
        if (PortfolioPosition.DEFAULT_CURRENCY.equals(normalized)) {
            return Optional.of(BigDecimal.ONE);
        }
        LocalDate today = LocalDate.now();
        if (!today.equals(cachedDate) || cachedRatesToEur.isEmpty()) {
            refreshRates(today);
        }
        return Optional.ofNullable(cachedRatesToEur.get(normalized));
    }

    private void refreshRates(LocalDate today) {
        Map<String, BigDecimal> parsed = fetchText(ecbUrl)
                .map(this::parseEcbXml)
                .filter(map -> !map.isEmpty())
                .orElseGet(() -> fetchText(frankfurterUrl)
                        .map(this::parseFrankfurterJson)
                        .orElse(Map.of()));
        if (!parsed.isEmpty()) {
            cachedRatesToEur = Map.copyOf(parsed);
            cachedDate = today;
        }
    }

    private Optional<String> fetchText(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HTTP_OK || response.body() == null) {
                LOG.warn("FX feed {} returned HTTP {}", url, response.statusCode());
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (IOException ex) {
            LOG.warn("Failed to fetch FX rates from {}: {}", url, ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("FX rate request interrupted for {}", url);
            return Optional.empty();
        }
    }

    private Map<String, BigDecimal> parseFrankfurterJson(String json) {
        Map<String, BigDecimal> rates = new HashMap<>();
        try {
            FrankfurterResponse parsed = objectMapper.readValue(json, FrankfurterResponse.class);
            if (parsed == null || parsed.rates() == null) {
                return rates;
            }
            for (Map.Entry<String, BigDecimal> entry : parsed.rates().entrySet()) {
                BigDecimal perEur = entry.getValue();
                if (entry.getKey() != null && perEur != null && perEur.signum() > 0) {
                    rates.put(entry.getKey().trim().toUpperCase(),
                            BigDecimal.ONE.divide(perEur, FX_SCALE, RoundingMode.HALF_UP));
                }
            }
        } catch (IOException ex) {
            LOG.warn("Failed to parse Frankfurter FX payload: {}", ex.getMessage());
        }
        return rates;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrankfurterResponse(Map<String, BigDecimal> rates) {
    }

    private Map<String, BigDecimal> parseEcbXml(String xml) {
        Map<String, BigDecimal> rates = new HashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList cubes = doc.getElementsByTagName("Cube");
            for (int i = 0; i < cubes.getLength(); i++) {
                Element element = (Element) cubes.item(i);
                String currency = element.getAttribute("currency");
                String rateAttr = element.getAttribute("rate");
                if (currency == null || currency.isBlank() || rateAttr == null || rateAttr.isBlank()) {
                    continue;
                }
                BigDecimal perEur = new BigDecimal(rateAttr.trim());
                if (perEur.signum() > 0) {
                    rates.put(currency.trim().toUpperCase(), BigDecimal.ONE.divide(perEur, FX_SCALE, RoundingMode.HALF_UP));
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException | NumberFormatException ex) {
            LOG.warn("Failed to parse ECB XML payload: {}", ex.getMessage());
        }
        return rates;
    }
}
