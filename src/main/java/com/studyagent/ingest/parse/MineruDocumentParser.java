package com.studyagent.ingest.parse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.DocumentPipelineProperties;
import com.studyagent.config.MineruProperties;
import com.studyagent.ingest.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MineruDocumentParser {

    public static final String VERSION = "mineru-2.5.4-pipeline-v1";
    public static final String MARKDOWN = "document.md";
    public static final String SOURCES = "document.sources.json";

    private static final String CLEANER_VERSION = "c1";
    private static final String VISION_PROMPT_VERSION = "v1";
    private static final String VISION_PROMPT = """
            用中文简要描述这张教材图示中实际可见的内容。写出可读的文字和图注、元素间的关系或箭头方向，
            以及明确标出的坐标轴与单位。只写能从图中读到的值，难以辨认的部分标为看不清。
            最多四句，直接输出说明文字。
            """;
    private static final Set<String> CLEANABLE_TYPES = Set.of("text", "title", "index", "list");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]");
    private static final Pattern CODE_FENCE = Pattern.compile("(?m)^ {0,3}(?:`{3,}|~{3,})");

    private final MineruProperties properties;
    private final ObjectMapper json;
    private final ObjectStorageService storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final long heartbeatMs;
    private final String descriptionFingerprint;
    private final String artifactVersion;

    public MineruDocumentParser(MineruProperties properties, DocumentPipelineProperties pipeline, ObjectMapper json,
            ObjectStorageService storage) {
        this.properties = properties;
        this.json = json;
        this.storage = storage;
        this.heartbeatMs = Math.max(1, Math.min(10_000, pipeline.leaseDuration().toMillis() / 3));
        var vision = properties.vision();
        this.descriptionFingerprint = sha256((vision.model() + "\n" + vision.endpoint() + "\n"
                + vision.maxTokens() + "\n" + VISION_PROMPT).getBytes(StandardCharsets.UTF_8));
        String profile = sha256((descriptionFingerprint + "\n" + vision.minSidePermille() + "\nrequire-descriptions")
                .getBytes(StandardCharsets.UTF_8));
        // parser_version is limited to 32 characters. Keep the full fingerprint in the image cache key.
        this.artifactVersion = VERSION.replace("-pipeline-v", "-p") + "-" + CLEANER_VERSION
                + (vision.enabled() ? "-" + VISION_PROMPT_VERSION + "-" + profile.substring(0, 10) : "-off");
    }

    public boolean supports(String filename) {
        return properties.enabled() && filename != null && filename.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|pptx|docx)$");
    }

    public String artifactVersion() {
        return artifactVersion;
    }

    public ParsedDocument parse(InputStream input, String filename, Long userId, Runnable renewLease) {
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
            HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(), renewLease);
            if (response.statusCode() != 200) {
                String detail = new String(response.body(), 0, Math.min(response.body().length, 1024), StandardCharsets.UTF_8);
                throw new BusinessException("MinerU HTTP " + response.statusCode() + ": " + detail);
            }
            if (!response.headers().firstValue("X-Parser-Version").orElse("").equals(VERSION)) {
                throw new BusinessException("MinerU 解析服务版本不匹配，预期 " + VERSION);
            }
            return prepareDocument(readResult(response.body()), userId, renewLease);
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

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, Runnable renewLease)
            throws InterruptedException, ExecutionException {
        var future = http.sendAsync(request, handler);
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

    private ParsedDocument prepareDocument(ParsedDocument raw, Long userId, Runnable renewLease)
            throws IOException, InterruptedException {
        StringBuilder markdown = new StringBuilder(raw.text().length());
        List<ParsedDocument.Block> blocks = new ArrayList<>();
        Set<String> seenImages = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (var source : raw.blocks()) {
            String text = raw.text().substring(source.startOffset(), source.endOffset());
            if (CLEANABLE_TYPES.contains(source.type()) && !CODE_FENCE.matcher(text).find()) {
                text = cleanText(text);
            }
            if (text.isBlank()) {
                continue;
            }
            var descriptions = new ArrayList<ImageDescription>();
            if (properties.vision().enabled() && "image".equals(source.type()) && !isSmallImage(source.bbox())) {
                for (String path : source.imagePaths()) {
                    byte[] image = raw.images().get(path);
                    String hash = sha256(image);
                    if (!seenImages.add(hash)) {
                        continue;
                    }
                    try {
                        descriptions.add(new ImageDescription(path, loadOrDescribe(userId, hash, image, renewLease)));
                    } catch (VisionFailure ex) {
                        failures.add("第 " + (source.pageIndex() + 1) + " 页 " + ex.getMessage());
                    }
                }
            }
            appendBlock(markdown, blocks, source, text, source.type(), source.imagePaths());
            for (var description : descriptions) {
                appendBlock(markdown, blocks, source, "[模型生成的图示说明] " + description.text(),
                        "image_description", List.of(description.path()));
            }
        }
        if (!failures.isEmpty()) {
            throw new BusinessException("MinerU 图示说明失败，涉及 " + failures.size() + " 张图。"
                    + String.join("；", failures.stream().limit(5).toList()));
        }
        return new ParsedDocument(markdown.toString(), blocks, raw.images());
    }

    private static String cleanText(String text) {
        return CONTROL_CHARACTERS.matcher(text).replaceAll("")
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?m)^[ \\t]+$", "")
                .replaceAll("(?m)(?<=\\S)[ \\t]$", "")
                // Two trailing spaces carry an explicit Markdown line break.
                .replaceAll("(?m)(?<=\\S)[ \\t]{2,}$", "  ")
                .replaceAll("\\n{3,}", "\n\n")
                .stripTrailing();
    }

    private static void appendBlock(StringBuilder markdown, List<ParsedDocument.Block> blocks,
            ParsedDocument.Block source, String text, String type, List<String> imagePaths) {
        if (!markdown.isEmpty()) {
            markdown.append("\n\n");
        }
        int start = markdown.length();
        markdown.append(text);
        List<String> headings = source.headingPath().stream().map(MineruDocumentParser::cleanText).toList();
        blocks.add(new ParsedDocument.Block(start, markdown.length(), source.pageIndex(), headings, type,
                source.bbox(), imagePaths, Map.of()));
    }

    private boolean isSmallImage(List<Integer> bbox) {
        return bbox.size() == 4
                && Math.min(bbox.get(2) - bbox.get(0), bbox.get(3) - bbox.get(1)) < properties.vision().minSidePermille();
    }

    private String loadOrDescribe(Long userId, String hash, byte[] image, Runnable renewLease)
            throws IOException, InterruptedException, VisionFailure {
        String key = "image-descriptions/" + userId + "/" + VISION_PROMPT_VERSION + "-" + descriptionFingerprint
                + "/" + hash + ".txt";
        if (storage.objectSize(key) != null) {
            try (InputStream cached = storage.getObject(key)) {
                return new String(cached.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        String description = requestDescription(image, renewLease);
        byte[] bytes = description.getBytes(StandardCharsets.UTF_8);
        storage.putObject(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
        return description;
    }

    private String requestDescription(byte[] image, Runnable renewLease) throws InterruptedException, VisionFailure {
        var vision = properties.vision();
        if (vision.apiKey() == null || vision.apiKey().isBlank()) {
            throw new VisionFailure("缺少视觉 API Key");
        }
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image);
        var parts = List.of(Map.of("type", "text", "text", VISION_PROMPT),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        var payload = Map.of("model", vision.model(), "max_tokens", vision.maxTokens(), "enable_thinking", false,
                "messages", List.of(Map.of("role", "user", "content", parts)));
        try {
            HttpRequest request = HttpRequest.newBuilder(vision.endpoint()).timeout(vision.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + vision.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                    .build();
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), renewLease);
            if (response.statusCode() != 200) {
                throw new VisionFailure("视觉 API HTTP " + response.statusCode());
            }
            var result = json.readTree(response.body());
            String content = result == null ? "" : result.path("choices").path(0).path("message").path("content").asText("");
            String description = cleanText(content).replaceAll("\\s+", " ").strip();
            if (description.isBlank()) {
                throw new VisionFailure("视觉 API 返回空说明");
            }
            return description;
        } catch (ExecutionException ex) {
            throw new VisionFailure("视觉 API 请求失败 " + ex.getCause().getClass().getSimpleName());
        } catch (IOException ex) {
            throw new VisionFailure("视觉 API 请求或响应 JSON 无效");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private record ImageDescription(String path, String text) {}

    private static final class VisionFailure extends Exception {
        private VisionFailure(String message) {
            super(message);
        }
    }
}
