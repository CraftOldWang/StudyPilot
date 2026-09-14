package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.LearningSession;

public interface LearningSessionMapper extends BaseMapper<LearningSession> {
    @org.apache.ibatis.annotations.Select("""
            SELECT s.id, s.knowledge_base_id,
                   COALESCE((SELECT LEFT(t.user_message, 48) FROM learning_turns t WHERE t.session_id = s.id ORDER BY t.created_at, t.id LIMIT 1), s.learning_goal) AS learning_goal,
                   s.status, s.updated_at, s.plan_run_id AS plan_id,
                   (SELECT COUNT(*) FROM knowledge_points p WHERE p.session_id = s.id AND p.status = 'COMPLETED') AS completed_points,
                   (SELECT COUNT(*) FROM knowledge_points p WHERE p.session_id = s.id) AS total_points
            FROM learning_sessions s
            WHERE s.user_id = #{userId} AND s.knowledge_base_id = #{knowledgeBaseId}
            ORDER BY s.updated_at DESC, s.id DESC
            """)
    java.util.List<com.studyagent.learning.LearningCatalog.SessionEntry> listCatalog(
            @org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("knowledgeBaseId") Long knowledgeBaseId);

    @org.apache.ibatis.annotations.Select("""
            SELECT DISTINCT p.outline_node_id FROM knowledge_points p
            JOIN learning_sessions s ON s.id = p.session_id
            WHERE s.user_id = #{userId} AND s.plan_run_id = #{runId}
              AND p.status = 'COMPLETED' AND p.outline_node_id IS NOT NULL
            """)
    java.util.Set<Long> completedOutlineNodes(@org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("runId") Long runId);
}
