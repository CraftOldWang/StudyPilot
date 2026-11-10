package com.studyagent.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.rerank")
public record RerankProperties(
        Path modelDirectory,
        @DefaultValue("30") int candidateSize,
        @DefaultValue("1024") int maxInputTokens
) {
    public static final int MAX_CANDIDATES = 50;
    // XLM-RoBERTa starts positions at 2 in the 8194-entry table.
    private static final int MODEL_MAX_INPUT_TOKENS = 8192;

    public RerankProperties {
        if (candidateSize < 20 || candidateSize > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Default rerank candidate size must be between 20 and 50");
        }
        if (maxInputTokens < 8 || maxInputTokens > MODEL_MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException("BGE input must allow between 8 and 8192 tokens per pair");
        }
    }
}
