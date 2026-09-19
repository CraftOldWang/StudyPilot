package com.studyagent.mapper;

import com.studyagent.model.LearningMemory;
import com.studyagent.model.LearningPreference;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface LearningMemoryMapper {
    @Select("SELECT * FROM learning_preferences WHERE user_id = #{userId}")
    LearningPreference preference(Long userId);

    @Insert("""
            INSERT INTO learning_preferences (user_id, explanation_style, learning_goal, memory_enabled, updated_at)
            VALUES (#{userId}, #{explanationStyle}, #{learningGoal}, #{memoryEnabled}, #{updatedAt})
            ON DUPLICATE KEY UPDATE explanation_style = VALUES(explanation_style),
              learning_goal = VALUES(learning_goal), memory_enabled = VALUES(memory_enabled), updated_at = VALUES(updated_at)
            """)
    void savePreference(LearningPreference preference);

    @Insert("""
            INSERT INTO learning_memories
              (id, user_id, knowledge_base_id, session_id, quiz_id, topic, correct_count, question_count, mistakes_json, included, recorded_at)
            VALUES (#{id}, #{userId}, #{knowledgeBaseId}, #{sessionId}, #{quizId}, #{topic},
              #{correctCount}, #{questionCount}, #{mistakesJson}, TRUE, #{recordedAt})
            ON DUPLICATE KEY UPDATE quiz_id = VALUES(quiz_id)
            """)
    void record(LearningMemory memory);

    @Select("""
            SELECT * FROM learning_memories WHERE user_id = #{userId} AND knowledge_base_id = #{knowledgeBaseId}
            ORDER BY recorded_at DESC, id DESC LIMIT #{limit}
            """)
    List<LearningMemory> recent(@Param("userId") Long userId, @Param("knowledgeBaseId") Long knowledgeBaseId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM learning_memories
            WHERE user_id = #{userId} AND knowledge_base_id = #{knowledgeBaseId} AND included = TRUE
              AND session_id != #{sessionId}
            ORDER BY (topic = #{topic}) DESC, recorded_at DESC, id DESC LIMIT #{limit}
            """)
    List<LearningMemory> recall(@Param("userId") Long userId, @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("sessionId") Long sessionId, @Param("topic") String topic, @Param("limit") int limit);

    @Update("""
            UPDATE learning_memories SET included = #{included}
            WHERE id = #{id} AND user_id = #{userId} AND knowledge_base_id = #{knowledgeBaseId}
            """)
    int setIncluded(@Param("userId") Long userId, @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("id") Long id, @Param("included") boolean included);
}
