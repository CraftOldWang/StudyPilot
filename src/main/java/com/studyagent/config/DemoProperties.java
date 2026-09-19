package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("study-agent.demo")
public record DemoProperties(@DefaultValue("false") boolean enabled,
                             @DefaultValue("") String publicOrigin,
                             @DefaultValue("60") int writesPerMinute) {
    public DemoProperties {
        if (enabled && (!publicOrigin.startsWith("https://") || publicOrigin.endsWith("/") || writesPerMinute < 1)) {
            throw new IllegalArgumentException("Demo requires an HTTPS public origin without trailing slash and a positive write limit");
        }
    }
}
