package de.in.mp.energy.vision;

/**
 * Supported provider presets for the OpenAI-compatible Vision API used to
 * extract meter readings from uploaded photos and portal screenshots.
 */
public enum VisionPreset {

    DISABLED("", "", false),
    GEMINI("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true),
    OPENAI("https://api.openai.com/v1", "gpt-4o-mini", true),
    OLLAMA("http://localhost:11434/v1", "qwen2.5vl:7b", false),
    CUSTOM("", "", false);

    private final String defaultBaseUrl;
    private final String defaultModel;
    private final boolean apiKeyRequired;

    VisionPreset(String defaultBaseUrl, String defaultModel, boolean apiKeyRequired) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.apiKeyRequired = apiKeyRequired;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean isApiKeyRequired() {
        return apiKeyRequired;
    }

    public static VisionPreset fromKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return DISABLED;
        }
        try {
            return VisionPreset.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return DISABLED;
        }
    }
}
