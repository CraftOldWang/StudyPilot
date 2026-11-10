package com.studyagent.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.ingest.mineru")
public record MineruProperties(
        boolean enabled,
        @DefaultValue("http://127.0.0.1:18001/file_parse") URI endpoint,
        @DefaultValue("30m") Duration timeout,
        @DefaultValue Vision vision
) {
    public record Vision(
            boolean enabled,
            @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions") URI endpoint,
            @DefaultValue("qwen3-vl-plus") String model,
            @DefaultValue("") String apiKey,
            @DefaultValue("60s") Duration timeout,
            @DefaultValue("35") int minSidePermille,
            @DefaultValue("512") int maxTokens
    ) {
        public Vision {
            if (minSidePermille < 0 || minSidePermille > 1000 || maxTokens <= 0
                    || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("无效的 MinerU 图示说明配置");
            }
        }
    }
}
