package com.studyagent.ingest.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.ingest.upload.FileUploadService;
import com.studyagent.identity.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口层，负责参数校验和响应包装，具体上传流程交给应用服务编排。
 */
@Validated
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;
    private final CurrentUserContext currentUserContext;

    /**
     * 上传前去重检查，客户端可据此决定是否走秒传。
     */
    @GetMapping("/dedup")
    public ApiResponse<FileDedupCheckResponse> checkDuplicate(
            @RequestParam Long knowledgeBaseId,
            @RequestParam String sha256) {
        return ApiResponse.ok(fileUploadService.checkDuplicate(currentUserContext.userId(), knowledgeBaseId, sha256));
    }

    /**
     * 小文件直传入口，成功后会创建文档并触发异步索引。
     */
    @PostMapping("/upload")
    public ApiResponse<UploadResultResponse> uploadSingle(
            @RequestParam @NotNull Long knowledgeBaseId,
            @RequestParam(required = false) Long logicalDocumentId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(fileUploadService.uploadSingle(currentUserContext.userId(), knowledgeBaseId, file, logicalDocumentId));
    }

    /**
     * 初始化大文件分片上传，返回上传会话或秒传结果。
     */
    @PostMapping("/multipart/init")
    public ApiResponse<InitMultipartUploadResponse> initMultipart(
            @Valid @ModelAttribute InitMultipartUploadRequest request) {
        return ApiResponse.ok(fileUploadService.initMultipart(currentUserContext.userId(), request));
    }

    /**
     * 上传指定分片，服务端会用 Redis Bitmap 记录该分片是否完成。
     */
    @PostMapping("/multipart/{uploadSessionId}/chunks/{chunkIndex}")
    public ApiResponse<Void> uploadChunk(
            @PathVariable Long uploadSessionId,
            @PathVariable @Min(0) int chunkIndex,
            @RequestParam("chunk") MultipartFile chunk) {
        fileUploadService.uploadChunk(currentUserContext.userId(), uploadSessionId, chunkIndex, chunk);
        return ApiResponse.ok(null);
    }

    /**
     * 查询分片上传状态，用于断点续传和前端进度展示。
     */
    @GetMapping("/multipart/{uploadSessionId}")
    public ApiResponse<MultipartUploadStatusResponse> multipartStatus(@PathVariable Long uploadSessionId) {
        return ApiResponse.ok(fileUploadService.multipartStatus(currentUserContext.userId(), uploadSessionId));
    }

    /**
     * 完成分片上传，服务端校验完整性后合并文件并触发文档处理。
     */
    @PostMapping("/multipart/complete")
    public ApiResponse<UploadResultResponse> completeMultipart(
            @Valid @ModelAttribute CompleteMultipartUploadRequest request) {
        return ApiResponse.ok(fileUploadService.completeMultipart(
                currentUserContext.userId(), request.uploadSessionId(), request.knowledgeBaseId()));
    }

    @PostMapping("/multipart/{uploadSessionId}/cancel")
    public ApiResponse<Void> cancelMultipart(@PathVariable Long uploadSessionId) {
        fileUploadService.cancelMultipart(currentUserContext.userId(), uploadSessionId);
        return ApiResponse.ok(null);
    }
}
