package de.in.mp.energy.vision;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.mp.energy.Meter;

/**
 * OpenAI-compatible Vision client for extracting meter readings from photos and
 * portal screenshots. Works identically against cloud APIs (Gemini, OpenAI)
 * and self-hosted endpoints (Ollama, LM Studio).
 */
@Service
public class VisionMeterClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisionMeterClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    VisionMeterClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Verifies reachability and credentials against {@code GET {baseUrl}/models}.
     */
    public void testConnection(VisionConfig config) {
        if (config == null || !config.isConfigured()) {
            throw new IllegalStateException("Vision configuration is incomplete");
        }
        listModels(config);
    }

    /**
     * Queries available model identifiers from {@code GET {baseUrl}/models}.
     * Normalizes optional {@code models/} prefixes returned by Gemini endpoints.
     */
    public List<String> listModels(VisionConfig config) {
        if (config == null || !config.canQueryModels()) {
            throw new IllegalStateException("Vision endpoint URL or API key is incomplete");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.normalizedBaseUrl() + "/models"))
                .timeout(CONNECT_TIMEOUT)
                .GET();
        String apiKey = config.effectiveApiKey();
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            return parseModelIds(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model query interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Model query failed: " + exception.getMessage(), exception);
        }
    }

    List<String> parseModelIds(String rawResponse) throws IOException {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(rawResponse);
        Set<String> models = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        collectModelIds(root.path("data"), models);
        collectModelIds(root.path("models"), models);
        return List.copyOf(models);
    }

    private static void collectModelIds(JsonNode arrayNode, Set<String> target) {
        if (!arrayNode.isArray()) {
            return;
        }
        for (JsonNode entry : arrayNode) {
            String rawId = firstNonBlank(entry, "id", "name", "model");
            if (rawId != null) {
                String normalized = rawId.startsWith("models/")
                        ? rawId.substring("models/".length()).trim()
                        : rawId.trim();
                if (!normalized.isBlank()) {
                    target.add(normalized);
                }
            }
        }
    }

    private static String firstNonBlank(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Sends an uploaded image together with the known meter metadata to the
     * configured Vision endpoint and parses the returned reading candidates.
     */
    public List<ExtractedMeterReading> extractReadings(
            VisionConfig config,
            byte[] imageBytes,
            String mimeType,
            LocalDate exifDate,
            List<Meter> activeMeters,
            Map<UUID, BigDecimal> latestValuesByMeter) {
        if (config == null || !config.isConfigured()) {
            throw new IllegalStateException("Vision configuration is incomplete");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image payload must not be empty");
        }
        String effectiveMime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String dataUri = "data:" + effectiveMime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        try {
            String requestBody = buildRequestJson(config, dataUri, exifDate, activeMeters, latestValuesByMeter);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.normalizedBaseUrl() + "/chat/completions"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            String apiKey = config.effectiveApiKey();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Vision API error HTTP " + response.statusCode()
                        + ": " + truncate(response.body()));
            }
            return parseChatCompletionResponse(response.body(), exifDate);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vision extraction interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Vision extraction failed: " + exception.getMessage(), exception);
        }
    }

    private String buildRequestJson(
            VisionConfig config,
            String dataUri,
            LocalDate exifDate,
            List<Meter> activeMeters,
            Map<UUID, BigDecimal> latestValuesByMeter) throws IOException {
        List<Map<String, Object>> meterCatalog = new ArrayList<>();
        if (activeMeters != null) {
            for (Meter meter : activeMeters) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", meter.getId() == null ? null : meter.getId().toString());
                item.put("name", meter.getName());
                item.put("type", meter.getType() == null ? null : meter.getType().name());
                item.put("serialNumber", meter.getSerialNumber());
                item.put("unit", meter.getUnit());
                item.put("recognitionHint", meter.getRecognitionHint());
                item.put("latestReading", meter.getId() == null ? null : latestValuesByMeter.get(meter.getId()));
                meterCatalog.add(item);
            }
        }

        String systemPrompt = """
                You extract utility meter readings from photos and energy portal screenshots.
                Return ONLY valid JSON with shape:
                {"readings":[{"meterId":"uuid-or-null","readingDate":"YYYY-MM-DD-or-null","value":1234.56,"unit":"kWh","delta":false,"confidence":0.95,"sourceHint":"short quote"}]}
                Rules:
                - One image may show multiple meters (e.g. two meters side by side) or multiple portal KPIs; return one entry per recognized meter.
                - Match meterId using serialNumber, OBIS code (e.g. 1.8.0 import vs 2.8.0 export), recognitionHint, handwritten labels, or meter name. If unsure, set meterId to null.
                - Set delta=true ONLY when the image shows a period/monthly total (e.g. PV portal monthly view) rather than a cumulative meter counter.
                - sourceHint must be a short text quote describing where the value was read (e.g. "OBIS 1.8.0 · 024392 kWh" or "Left meter · Whg A").
                """;

        String userText = "EXIF capture date: " + (exifDate != null ? exifDate : "unknown")
                + "\nActive meters:\n" + objectMapper.writeValueAsString(meterCatalog);

        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUri));
        Map<String, Object> textPart = Map.of(
                "type", "text",
                "text", userText);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.normalizedModel());
        payload.put("temperature", 0.0);
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", List.of(textPart, imagePart))));

        return objectMapper.writeValueAsString(payload);
    }

    List<ExtractedMeterReading> parseChatCompletionResponse(String rawResponse, LocalDate fallbackDate)
            throws IOException {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            return List.of();
        }
        String cleaned = stripCodeFences(contentNode.asText());
        JsonNode parsed = objectMapper.readTree(cleaned);
        JsonNode readingsNode = parsed.path("readings");
        if (!readingsNode.isArray()) {
            return List.of();
        }

        List<ExtractedMeterReading> result = new ArrayList<>();
        for (JsonNode entry : readingsNode) {
            BigDecimal value = entry.hasNonNull("value") && entry.get("value").isNumber()
                    ? entry.get("value").decimalValue()
                    : parseDecimal(entry.path("value").asText(null));
            if (value == null) {
                continue;
            }
            UUID meterId = parseUuid(entry.path("meterId").asText(null));
            LocalDate date = parseDate(entry.path("readingDate").asText(null), fallbackDate);
            String unit = entry.path("unit").asText("");
            boolean delta = entry.path("delta").asBoolean(false);
            double confidence = entry.hasNonNull("confidence") ? entry.get("confidence").asDouble(0.5) : 0.5;
            String sourceHint = entry.path("sourceHint").asText("");
            result.add(new ExtractedMeterReading(meterId, date, value, unit, delta, confidence, sourceHint));
        }
        return result;
    }

    private static String stripCodeFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return fallback != null ? fallback : LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception exception) {
            return fallback != null ? fallback : LocalDate.now();
        }
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
