package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.learning.conversation")
public record LearningConversationProperties(
        @DefaultValue("8000") int thresholdTokens,
        @DefaultValue("700") int summaryTokens,
        @DefaultValue("3000") int replyTokens,
        @DefaultValue("5") int maxIterations,
        @DefaultValue("600") int leaseSeconds,
        @DefaultValue("LOCAL") String compressionStrategy) {
    public LearningConversationProperties {
        if (thresholdTokens < 1000 || summaryTokens < 100 || replyTokens < 500 || maxIterations < 1 || leaseSeconds < 60) {
            throw new IllegalArgumentException("Invalid learning conversation budget");
        }
        if (!java.util.Set.of("NONE", "THRESHOLD", "WHOLE_HISTORY", "LOCAL").contains(compressionStrategy)) {
            throw new IllegalArgumentException("Unsupported compression strategy");
        }
    }
}
