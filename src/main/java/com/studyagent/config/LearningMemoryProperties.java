package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("study-agent.learning.memory")
public record LearningMemoryProperties(@DefaultValue("1200") int contextTokens, @DefaultValue("4") int recallLimit) {
    public LearningMemoryProperties {
        if (contextTokens < 600 || recallLimit < 1 || recallLimit > 10) {
            throw new IllegalArgumentException("Invalid learning memory budget");
        }
    }
}
