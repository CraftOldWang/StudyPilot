package com.studyagent.ingest.upload;

import com.studyagent.common.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class UploadFileSupport {
    private UploadFileSupport() {}

    static String normalizeHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) throw new BusinessException("文件 SHA-256 格式不正确");
        return hash.toLowerCase(Locale.ROOT);
    }

    static String filename(String name) {
        if (name == null || name.isBlank()) throw new BusinessException("文件名不能为空");
        String safe = name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (safe.isBlank() || safe.length() > 255) throw new BusinessException("文件名长度不合法");
        return safe;
    }

    static String contentType(String type) {
        return type == null || type.isBlank() ? "application/octet-stream" : type;
    }

    static void validateType(String name, String type) {
        String lower = filename(name).toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String expected = switch (dot < 0 ? "" : lower.substring(dot)) {
            case ".pdf" -> "application/pdf";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".txt" -> "text/plain";
            case ".md", ".markdown" -> "text/markdown";
            case ".mp4" -> "video/mp4";
            case ".m4a" -> "audio/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            default -> throw new BusinessException("支持 TXT、Markdown、PDF、PPTX、DOCX、MP4、M4A、MP3、WAV 文件");
        };
        String actual = contentType(type).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (actual.equals("audio/x-m4a")) actual = "audio/mp4";
        if (actual.equals("audio/x-wav") || actual.equals("audio/wave")) actual = "audio/wav";
        if (actual.equals("audio/mp3")) actual = "audio/mpeg";
        if (!actual.equals(expected) && !actual.equals("application/octet-stream")
                && !(expected.equals("text/markdown") && actual.equals("text/plain"))) {
            throw new BusinessException("文件扩展名与 content-type 不匹配");
        }
    }

    static VerifiedBytes hash(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
            bytes += count;
        }
        return new VerifiedBytes(HexFormat.of().formatHex(digest.digest()), bytes);
    }

    static String dedupLock(Long userId, Long kbId, String hash) {
        return "lock:file:dedup:" + userId + ":" + kbId + ":" + hash;
    }

    record VerifiedBytes(String sha256, long bytes) {}
}
