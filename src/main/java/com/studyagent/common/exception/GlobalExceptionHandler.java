package com.studyagent.common.exception;

import com.studyagent.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，将异常转换为统一 ApiResponse。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理主动抛出的业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 处理 Bean Validation 参数校验异常。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception ex) {
        log.warn("参数校验异常: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, ex.getMessage()));
    }

    /**
     * 处理单次 multipart 请求超过限制的情况。
     *
     * <p>大文件“直传”仍然会受 Spring/Tomcat 单请求体大小限制；真正的大文件上传应优先走前端分片，
     * 每次请求只发送一个 chunk，避免整文件请求在进入 Controller 前就被容器拒绝。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        log.warn("上传请求超过 multipart 限制: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(413, "上传请求超过后端单次请求大小限制，请调大 multipart 限制或使用前端分片上传"));
    }

    /**
     * 处理未预期异常，返回 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务处理失败，请稍后重试；详细原因已记录在服务端日志中"));
    }
}
