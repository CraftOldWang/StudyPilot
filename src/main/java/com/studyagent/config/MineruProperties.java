package com.studyagent.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.ingest.mineru")
public record MineruProperties(
        boolean enabled,
        @DefaultValue("http://127.0.0.1:18001/file_parse") URI endpoint,
        @DefaultValue("30m") Duration timeout
) {
}
