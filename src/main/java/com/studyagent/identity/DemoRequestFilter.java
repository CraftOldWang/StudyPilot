package com.studyagent.identity;

import com.studyagent.config.DemoProperties;
import com.studyagent.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "study-agent.demo.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DemoRequestFilter extends OncePerRequestFilter {
    private final DemoProperties properties;
    private final ObjectMapper json;
    private long minute;
    private int writes;

    public DemoRequestFilter(DemoProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            String origin = request.getHeader("Origin");
            if (origin != null && !properties.publicOrigin().equals(origin)) {
                reject(response, 403, "演示站不接受跨站写入请求"); return;
            }
            if (!allowWrite()) { response.setHeader("Retry-After", "60"); reject(response, 429, "演示站操作较多，请稍后重试"); return; }
            if ("/api/files/multipart/init".equals(request.getRequestURI())) {
                String name = request.getParameter("filename");
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(pdf|pptx|txt|md|markdown)$")) {
                    reject(response, 400, "在线演示仅支持 PDF、PPTX、TXT 和 Markdown，音视频请使用本地版"); return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    // All visitors share one demo, so the write budget is shared rather than keyed by a spoofable header.
    private synchronized boolean allowWrite() {
        long now = Instant.now().getEpochSecond() / 60;
        if (minute != now) { minute = now; writes = 0; }
        return ++writes <= properties.writesPerMinute();
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), ApiResponse.fail(status, message));
    }
}
