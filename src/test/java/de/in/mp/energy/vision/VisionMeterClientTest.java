package de.in.mp.energy.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterType;

class VisionMeterClientTest {

    @Test
    void visionConfigValidatesCloudAndLocalPresets() {
        assertThat(VisionConfig.disabled().isConfigured()).isFalse();
        assertThat(new VisionConfig(VisionPreset.GEMINI, VisionPreset.GEMINI.defaultBaseUrl(),
                VisionPreset.GEMINI.defaultModel(), "").isConfigured()).isFalse();
        assertThat(new VisionConfig(VisionPreset.GEMINI, VisionPreset.GEMINI.defaultBaseUrl(),
                VisionPreset.GEMINI.defaultModel(), "secret").isConfigured()).isTrue();
        assertThat(new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1/",
                "qwen2.5vl:7b", "").isConfigured()).isTrue();
        assertThat(new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1/",
                "qwen2.5vl:7b", "").normalizedBaseUrl()).isEqualTo("http://localhost:11434/v1");
    }

    @Test
    void normalizesMwhToKwh() {
        assertThat(ExtractedMeterReading.normalizeToTargetUnit(
                new BigDecimal("1.03"), "MWh", "kWh")).isEqualByComparingTo("1030.000");
        assertThat(ExtractedMeterReading.normalizeToTargetUnit(
                new BigDecimal("752.75"), "kWh", "kWh")).isEqualByComparingTo("752.750");
    }

    @Test
    void roundsElectricityKwhAndWaterCubicMetersToWholeIntegers() {
        Meter grid = new Meter();
        grid.setType(MeterType.GRID_IMPORT);
        grid.setUnit("kWh");

        Meter water = new Meter();
        water.setType(MeterType.WATER);
        water.setUnit("m3");

        Meter gas = new Meter();
        gas.setType(MeterType.GAS);
        gas.setUnit("m3");

        assertThat(ExtractedMeterReading.normalizeForMeter(new BigDecimal("1.03"), "MWh", grid))
                .isEqualTo(new BigDecimal("1030"));
        assertThat(ExtractedMeterReading.normalizeForMeter(new BigDecimal("752.75"), "kWh", grid))
                .isEqualTo(new BigDecimal("753"));
        assertThat(ExtractedMeterReading.normalizeForMeter(new BigDecimal("418.73"), "m³", water))
                .isEqualTo(new BigDecimal("419"));
        assertThat(ExtractedMeterReading.normalizeForMeter(new BigDecimal("1234.56"), "m3", gas))
                .isEqualByComparingTo("1234.560");
    }

    @Test
    void exifExtractorHandlesInvalidOrEmptyBytesGracefully() {
        assertThat(ExifDateExtractor.extractCaptureDate(null)).isEmpty();
        assertThat(ExifDateExtractor.extractCaptureDate(new byte[] { 1, 2, 3 })).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractReadingsSendsOpenAiCompatibleRequestAndParsesCodeFencedJsonArray() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);

        UUID meter1 = UUID.randomUUID();
        UUID meter2 = UUID.randomUUID();
        LocalDate fallbackDate = LocalDate.of(2026, 10, 31);

        String responseJson = """
                {
                  "choices": [{
                    "message": {
                      "content": "```json\\n{\\"readings\\":[{\\"meterId\\":\\"%s\\",\\"readingDate\\":null,\\"value\\":24392,\\"unit\\":\\"kWh\\",\\"delta\\":false,\\"confidence\\":0.97,\\"sourceHint\\":\\"OBIS 1.8.0\\"},{\\"meterId\\":\\"%s\\",\\"readingDate\\":\\"2026-10-31\\",\\"value\\":752.75,\\"unit\\":\\"kWh\\",\\"delta\\":true,\\"confidence\\":0.91,\\"sourceHint\\":\\"PV Ertrag\\"}]}\\n```"
                    }
                  }]
                }
                """.formatted(meter1, meter2);

        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Meter m1 = new Meter();
        m1.setId(meter1);
        m1.setName("Strom Bezug");
        m1.setType(MeterType.GRID_IMPORT);
        m1.setRecognitionHint("OBIS 1.8.0");

        VisionMeterClient client = new VisionMeterClient(httpClient);
        VisionConfig config = new VisionConfig(VisionPreset.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "test-key");

        List<ExtractedMeterReading> readings = client.extractReadings(
                config, new byte[] { 10, 20 }, "image/jpeg", fallbackDate, List.of(m1), Map.of());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().uri().toString())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
        assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
                .contains("Bearer test-key");

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).meterId()).isEqualTo(meter1);
        assertThat(readings.get(0).readingDate()).isEqualTo(fallbackDate);
        assertThat(readings.get(0).value()).isEqualByComparingTo("24392");
        assertThat(readings.get(0).delta()).isFalse();
        assertThat(readings.get(0).sourceHint()).isEqualTo("OBIS 1.8.0");

        assertThat(readings.get(1).meterId()).isEqualTo(meter2);
        assertThat(readings.get(1).delta()).isTrue();
    }

    @Test
    void canQueryModelsDoesNotRequireModelName() {
        assertThat(new VisionConfig(VisionPreset.GEMINI, VisionPreset.GEMINI.defaultBaseUrl(), "", "key")
                .canQueryModels()).isTrue();
        assertThat(new VisionConfig(VisionPreset.GEMINI, VisionPreset.GEMINI.defaultBaseUrl(), "", "")
                .canQueryModels()).isFalse();
        assertThat(new VisionConfig(VisionPreset.OLLAMA, "http://localhost:11434/v1", "", "")
                .canQueryModels()).isTrue();
        assertThat(VisionConfig.disabled().canQueryModels()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listModelsParsesOpenAiAndGeminiModelIdsSortedAndNormalized() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "object": "list",
                  "data": [
                    { "id": "models/gemini-2.5-pro" },
                    { "id": "models/gemini-2.5-flash" },
                    { "id": "gemini-2.5-flash" },
                    { "id": "llama3.2-vision:11b" }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        VisionMeterClient client = new VisionMeterClient(httpClient);
        VisionConfig config = new VisionConfig(VisionPreset.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/openai", "", "test-key");

        List<String> models = client.listModels(config);

        assertThat(models).containsExactly("gemini-2.5-flash", "gemini-2.5-pro", "llama3.2-vision:11b");
    }

    @Test
    void listModelsRejectsIncompleteEndpointConfiguration() {
        VisionMeterClient client = new VisionMeterClient(mock(HttpClient.class));
        assertThatThrownBy(() -> client.listModels(VisionConfig.disabled()))
                .isInstanceOf(IllegalStateException.class);
    }
}
