package de.in.mp.finance;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link MarketPriceProvider} backed by Yahoo Finance's public chart and search
 * endpoints. Prioritizes German EUR listings ({@code .DE}, {@code .F}) when
 * resolving an ISIN to a ticker symbol.
 */
@Component
public class YahooMarketPriceProvider implements MarketPriceProvider {

    private static final Logger LOG = LoggerFactory.getLogger(YahooMarketPriceProvider.class);

    private static final String DEFAULT_BASE_URL = "https://query1.finance.yahoo.com";
    private static final String CHART_PATH = "/v8/finance/chart/";
    private static final String CHART_QUERY = "?interval=1d&range=1d";
    private static final String SEARCH_PATH = "/v1/finance/search?q=";
    private static final String SEARCH_QUERY = "&quotesCount=10&newsCount=0";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; MoneyPoly/1.0)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int HTTP_OK = 200;
    private static final List<String> PREFERRED_SUFFIXES = List.of(".DE", ".F", ".AS", ".PA");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public YahooMarketPriceProvider(@Value("${moneypoly.market-data.yahoo-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), baseUrl);
    }

    YahooMarketPriceProvider(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : DEFAULT_BASE_URL;
    }

    @Override
    public Optional<MarketQuote> fetchQuote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        String trimmed = symbol.trim().toUpperCase();
        String url = baseUrl + CHART_PATH + encode(trimmed) + CHART_QUERY;
        return executeGet(url).flatMap(body -> parseChartResponse(trimmed, body));
    }

    @Override
    public Optional<String> resolveTickerByIsin(String isin) {
        if (isin == null || isin.isBlank()) {
            return Optional.empty();
        }
        String trimmed = isin.trim().toUpperCase();
        String url = baseUrl + SEARCH_PATH + encode(trimmed) + SEARCH_QUERY;
        return executeGet(url).flatMap(this::parseSearchResponse);
    }

    private Optional<String> executeGet(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HTTP_OK) {
                LOG.debug("Market data request {} returned HTTP {}", url, response.statusCode());
                return Optional.empty();
            }
            return Optional.ofNullable(response.body());
        } catch (IOException ex) {
            LOG.warn("Market data request {} failed: {}", url, ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("Market data request {} interrupted", url);
            return Optional.empty();
        }
    }

    private Optional<MarketQuote> parseChartResponse(String fallbackSymbol, String body) {
        try {
            ChartResponse parsed = objectMapper.readValue(body, ChartResponse.class);
            if (parsed == null || parsed.chart() == null || parsed.chart().result() == null
                    || parsed.chart().result().isEmpty()) {
                return Optional.empty();
            }
            ChartMeta meta = parsed.chart().result().get(0).meta();
            if (meta == null || meta.regularMarketPrice() == null || meta.regularMarketPrice().signum() <= 0) {
                return Optional.empty();
            }
            String symbol = (meta.symbol() != null && !meta.symbol().isBlank()) ? meta.symbol() : fallbackSymbol;
            String currency = (meta.currency() != null && !meta.currency().isBlank())
                    ? meta.currency().trim().toUpperCase()
                    : PortfolioPosition.DEFAULT_CURRENCY;
            return Optional.of(new MarketQuote(symbol, meta.regularMarketPrice(), currency, Instant.now()));
        } catch (IOException ex) {
            LOG.warn("Failed to parse Yahoo chart response for {}: {}", fallbackSymbol, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> parseSearchResponse(String body) {
        try {
            SearchResponse parsed = objectMapper.readValue(body, SearchResponse.class);
            if (parsed == null || parsed.quotes() == null || parsed.quotes().isEmpty()) {
                return Optional.empty();
            }
            return parsed.quotes().stream()
                    .map(SearchQuote::symbol)
                    .filter(s -> s != null && !s.isBlank())
                    .min(Comparator.comparingInt(YahooMarketPriceProvider::listingPriority));
        } catch (IOException ex) {
            LOG.warn("Failed to parse Yahoo search response: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static int listingPriority(String symbol) {
        String upper = symbol.toUpperCase();
        for (int i = 0; i < PREFERRED_SUFFIXES.size(); i++) {
            if (upper.endsWith(PREFERRED_SUFFIXES.get(i))) {
                return i;
            }
        }
        return PREFERRED_SUFFIXES.size();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResponse(ChartEnvelope chart) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartEnvelope(List<ChartResult> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResult(ChartMeta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartMeta(String symbol, String currency, BigDecimal regularMarketPrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchQuote> quotes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchQuote(String symbol, String exchange, String quoteType) {
    }
}
