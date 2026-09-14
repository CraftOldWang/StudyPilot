package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_sessions")
public class LearningSession {

    @TableId("id")
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("plan_run_id")
    private Long planRunId;

    @TableField("learning_goal")
    private String learningGoal;

    @TableField("agentscope_session_id")
    private String agentscopeSessionId;

    @TableField("active_knowledge_point_id")
    private Long activeKnowledgePointId;

    @TableField("active_turn_id")
    private Long activeTurnId;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
