package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("documents")
public class Document {

    @TableId("id")
    private Long id;

    @TableField("file_record_id")
    private Long fileRecordId;

    @TableField("user_id")
    private Long userId;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    private Long logicalDocumentId;
    private Long previousDocumentId;

    @TableField("title")
    private String title;

    @TableField("content_type")
    private String contentType;

    @TableField("pipeline_status")
    private String pipelineStatus;

    @TableField("error_message")
    private String errorMessage;

    @TableField("parser_version")
    private String parserVersion;

    @TableField("chunker_version")
    private String chunkerVersion;

    @TableField("parsed_text_key")
    private String parsedTextKey;
    @TableField("parsed_text_hash")
    private String parsedTextHash;
    @TableField("asr_result_key")
    private String asrResultKey;
    @TableField("asr_metadata_json")
    private String asrMetadataJson;
    @TableField("last_successful_stage")
    private String lastSuccessfulStage;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @TableField("processing_token")
    private String processingToken;
    @TableField("lease_until")
    private LocalDateTime leaseUntil;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("index_target")
    private String indexTarget;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
