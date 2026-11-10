package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 分片上传会话实体，保存一次大文件上传的业务状态和完成结果。
 *
 * <p>upload_parts 保存持久化 ETag；Redis Bitmap 是可以从分片事实重建的进度缓存。</p>
 */
@Getter
@Setter
@TableName("upload_sessions")
public class UploadSession {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long logicalDocumentId;
    @TableField("file_hash")
    private String fileHash;
    private String filename;
    private String contentType;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long fileSize;
    private Long completedFileId;
    private Long completedDocumentId;
    private String status;
    private String storageUploadId;
    private String storageKey;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
