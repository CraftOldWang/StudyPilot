package com.studyagent.ingest.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 初始化分片上传的请求参数，包含客户端提前计算出的 SHA-256 和分片规划。
 */
public record InitMultipartUploadRequest(
        @NotNull Long knowledgeBaseId,
        @NotBlank String filename,
        String contentType,
        @NotBlank String sha256,
        @Min(1) long fileSize,
        @Min(1) int chunkSize,
        @Min(1) int totalChunks,
        Long logicalDocumentId
) {
}
