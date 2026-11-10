package com.studyagent.ingest.parse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.DocumentPipelineProperties;
import com.studyagent.config.MineruProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MineruDocumentParser {

    public static final String VERSION = "mineru-2.5.4-pipeline-v1";
    public static final String MARKDOWN = "document.md";
    public static final String SOURCES = "document.sources.json";

    private final MineruProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final long heartbeatMs;

    public MineruDocumentParser(MineruProperties properties, DocumentPipelineProperties pipeline, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.heartbeatMs = Math.max(1, Math.min(10_000, pipeline.leaseDuration().toMillis() / 3));
    }

    public boolean supports(String filename) {
        return properties.enabled() && filename != null && filename.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|pptx|docx)$");
    }

    public ParsedDocument parse(InputStream input, String filename, Runnable renewLease) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("studypilot-mineru-", ".upload");
            try (var output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
            renewLease.run();
            String boundary = "studyagent-" + UUID.randomUUID();
            String suffix = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document"
                    + suffix + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            HttpRequest request = HttpRequest.newBuilder(properties.endpoint()).timeout(properties.timeout())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofString(prefix),
                            HttpRequest.BodyPublishers.ofFile(temporary),
                            HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")))
                    .build();
            HttpResponse<byte[]> response = send(request, renewLease);
            if (response.statusCode() != 200) {
                String detail = new String(response.body(), 0, Math.min(response.body().length, 1024), StandardCharsets.UTF_8);
                throw new BusinessException("MinerU HTTP " + response.statusCode() + ": " + detail);
            }
            if (!response.headers().firstValue("X-Parser-Version").orElse("").equals(VERSION)) {
                throw new BusinessException("MinerU 解析服务版本不匹配，预期 " + VERSION);
            }
            return readResult(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待 MinerU 解析被中断");
        } catch (ExecutionException ex) {
            throw new BusinessException("MinerU 请求失败: " + ex.getCause());
        } catch (IOException ex) {
            throw new BusinessException("MinerU 文件或解析产物读取失败: " + ex.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    log.warn("清理 MinerU 临时文件失败: {}", temporary, ex);
                }
            }
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, Runnable renewLease)
            throws InterruptedException, ExecutionException {
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            while (true) {
                try {
                    return future.get(heartbeatMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waiting) {
                    renewLease.run();
                }
            }
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private ParsedDocument readResult(byte[] archive) throws IOException {
        String markdown = null;
        List<ParsedDocument.Block> blocks = null;
        var images = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (MARKDOWN.equals(name)) {
                    markdown = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (SOURCES.equals(name)) {
                    blocks = json.readValue(zip.readAllBytes(), new TypeReference<List<ParsedDocument.Block>>() {});
                } else if (name.matches("images/[a-zA-Z0-9_.-]+\\.jpg")) {
                    images.put(name, zip.readAllBytes());
                }
            }
        }
        if (markdown == null || markdown.isBlank() || blocks == null || blocks.isEmpty()) {
            throw new BusinessException("MinerU 产物缺少 Markdown 或来源坐标");
        }
        for (var block : blocks) {
            if (block.startOffset() < 0 || block.endOffset() <= block.startOffset()
                    || block.endOffset() > markdown.length() || !images.keySet().containsAll(block.imagePaths())) {
                throw new BusinessException("MinerU 产物包含无效来源坐标或缺少原图");
            }
        }
        return new ParsedDocument(markdown, blocks, images);
    }
}
