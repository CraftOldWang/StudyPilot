package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("knowledge_points")
public class KnowledgePoint {

    @TableId("id")
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    @TableField("outline_node_id")
    private Long outlineNodeId;

    @TableField("user_id")
    private Long userId;

    @TableField("sequence_no")
    private Integer sequenceNo;

    @TableField("topic")
    private String topic;

    @TableField("subtopics_json")
    private String subtopicsJson;

    @TableField("estimated_minutes")
    private Integer estimatedMinutes;

    @TableField("status")
    private String status;

    @TableField("explanation")
    private String explanation;

    @TableField("error_message")
    private String errorMessage;

    @TableField("chapter_id")
    private Long chapterId;
    @TableField("chapter_title")
    private String chapterTitle;
    @TableField("priority")
    private String priority;
    @TableField("sources_json")
    private String sourcesJson;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
