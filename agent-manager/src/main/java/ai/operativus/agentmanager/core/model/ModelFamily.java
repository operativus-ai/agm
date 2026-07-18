package ai.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Standardizes the resolution of LLM providers based on explicit identifiers 
 * or implicitly matched string prefixes across the platform.
 */
public enum ModelFamily {
    OPENAI("OPENAI"),
    ANTHROPIC("ANTHROPIC"),
    GOOGLE("GOOGLE"),
    OLLAMA("OLLAMA"),
    UNKNOWN("UNKNOWN");

    private final String family;

    ModelFamily(String family) {
        this.family = family;
    }

    @JsonValue
    public String getFamily() {
        return family;
    }

    /**
     * Resolves the ModelFamily from a raw provider string (e.g., "openai", "gpt-4o").
     */
    public static ModelFamily fromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return UNKNOWN;
        }
        
        String lower = modelId.toLowerCase();
        
        if (lower.startsWith("openai") || lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3")) {
            return OPENAI;
        } else if (lower.startsWith("anthropic") || lower.startsWith("claude")) {
            return ANTHROPIC;
        } else if (lower.startsWith("google") || lower.startsWith("gemini")) {
            return GOOGLE;
        } else if (lower.startsWith("ollama")) {
            return OLLAMA;
        }
        
        return UNKNOWN;
    }

    @JsonCreator
    public static ModelFamily fromString(String value) {
        if (value == null) return null;
        for (ModelFamily fam : values()) {
            if (fam.family.equalsIgnoreCase(value) || fam.name().equalsIgnoreCase(value)) {
                return fam;
            }
        }
        return UNKNOWN;
    }
}
