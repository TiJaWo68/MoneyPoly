package de.in.mp.finance.banksync;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.mp.finance.AccountType;

/**
 * Shared HTTP, JSON, and normalization utilities for Open-Banking providers.
 */
final class OpenBankingHttpSupport {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT = "MoneyPoly/1.0 (OpenBanking AIS ReadOnly)";
    private static final List<String> SAVINGS_HINTS = List.of("tagesgeld", "spar", "pocket", "savings", "svgs");
    private static final List<String> INVESTMENT_HINTS = List.of("depot", "wertpapier", "broker", "verrechnung", "trade republic", "scalable");
    private static final List<String> LOAN_HINTS = List.of("darlehen", "kredit", "loan");

    private OpenBankingHttpSupport() {
    }

    static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static String normalizeBaseUrl(String baseUrl, String defaultUrl) {
        String value = (baseUrl == null || baseUrl.isBlank()) ? defaultUrl : baseUrl.trim();
        return value.replaceAll("/+$", "");
    }

    static JsonNode getJson(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String url,
            Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return sendAndParse(httpClient, objectMapper, builder.build(), url);
    }

    static JsonNode postJson(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String url,
            Object body,
            Map<String, String> headers) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            return sendAndParse(httpClient, objectMapper, builder.build(), url);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize Open-Banking request payload", ex);
        }
    }

    private static JsonNode sendAndParse(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            HttpRequest request,
            String url) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Open-Banking request failed with HTTP "
                        + response.statusCode() + " for " + url);
            }
            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (IOException ex) {
            throw new IllegalStateException("Open-Banking HTTP call failed for " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Open-Banking HTTP call interrupted for " + url, ex);
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static OffsetDateTime parseOffsetDateTime(String raw, OffsetDateTime fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (RuntimeException ex) {
            try {
                return LocalDate.parse(raw.trim()).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    static LocalDate parseLocalDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, Math.min(10, raw.trim().length())));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    static AccountType suggestAccountType(String name, String rawType) {
        if (rawType != null && !rawType.isBlank()) {
            String upper = rawType.trim().toUpperCase(Locale.ROOT);
            if ("CHECKING".equals(upper) || "CACC".equals(upper)) {
                return AccountType.CHECKING;
            }
            if ("SAVINGS".equals(upper) || "SVGS".equals(upper)) {
                return AccountType.SAVINGS;
            }
            if ("INVESTMENT".equals(upper) || "BROKERAGE".equals(upper)) {
                return AccountType.INVESTMENT;
            }
            if ("LOAN".equals(upper)) {
                return AccountType.LOAN;
            }
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (SAVINGS_HINTS.stream().anyMatch(lower::contains)) {
            return AccountType.SAVINGS;
        }
        if (INVESTMENT_HINTS.stream().anyMatch(lower::contains)) {
            return AccountType.INVESTMENT;
        }
        if (LOAN_HINTS.stream().anyMatch(lower::contains)) {
            return AccountType.LOAN;
        }
        return AccountType.CHECKING;
    }

    static String normalizeIban(String iban) {
        if (iban == null || iban.isBlank()) {
            return null;
        }
        return iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static String readJsonField(ObjectMapper objectMapper, String payload, String fieldName, String providerLabel) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException(providerLabel + " session payload must not be empty");
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String value = textOrNull(node, fieldName);
            if (value == null) {
                throw new IllegalArgumentException("Missing " + fieldName + " in " + providerLabel + " session payload");
            }
            return value;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid " + providerLabel + " session payload", ex);
        }
    }

    static String writeJsonPayload(ObjectMapper objectMapper, Map<String, String> data, String providerLabel) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize " + providerLabel + " session payload", ex);
        }
    }
}
