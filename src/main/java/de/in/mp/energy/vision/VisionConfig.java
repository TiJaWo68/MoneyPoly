package de.in.mp.energy.vision;

/**
 * Runtime configuration for OpenAI-compatible Vision meter reading extraction.
 */
public record VisionConfig(
        VisionPreset preset,
        String baseUrl,
        String model,
        String apiKey) {

    public static final String ENV_API_KEY = "MONEYPOLY_VISION_API_KEY";

    public static VisionConfig disabled() {
        return new VisionConfig(VisionPreset.DISABLED, "", "", "");
    }

    public boolean canQueryModels() {
        if (preset == null || preset == VisionPreset.DISABLED) {
            return false;
        }
        if (normalizedBaseUrl().isBlank()) {
            return false;
        }
        return !preset.isApiKeyRequired() || !effectiveApiKey().isBlank();
    }

    public boolean isConfigured() {
        return canQueryModels() && !normalizedModel().isBlank();
    }

    public String normalizedBaseUrl() {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String normalizedModel() {
        return model == null ? "" : model.trim();
    }

    public String effectiveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String env = System.getenv(ENV_API_KEY);
        return env == null ? "" : env.trim();
    }
}
