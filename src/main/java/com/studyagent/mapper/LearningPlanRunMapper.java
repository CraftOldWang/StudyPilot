package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.LearningPlanRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LearningPlanRunMapper extends BaseMapper<LearningPlanRun> {
    @Select("""
            SELECT id FROM learning_plan_runs
            WHERE user_id = #{userId} AND knowledge_base_id = #{knowledgeBaseId}
              AND JSON_UNQUOTE(JSON_EXTRACT(input_json, '$.version')) = #{version}
            ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    Long currentId(@Param("userId") Long userId, @Param("knowledgeBaseId") Long knowledgeBaseId,
                   @Param("sessionId") Long sessionId, @Param("version") String version);
}
