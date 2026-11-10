package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

/**
 * 分片上传会话 Mapper，封装会话恢复所需的查询。
 */
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    /**
     * 查询同一用户、知识库和文件哈希下仍可继续上传的最新会话。
     */
    @Select("""
            SELECT *
            FROM upload_sessions
            WHERE user_id = #{userId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND (logical_document_id = #{logicalDocumentId,jdbcType=BIGINT}
                   OR (logical_document_id IS NULL AND #{logicalDocumentId,jdbcType=BIGINT} IS NULL))
              AND file_hash = #{fileHash}
              AND status IN ('INITIALIZING', 'UPLOADING', 'MERGING', 'VERIFYING', 'VERIFIED')
              AND storage_key IS NOT NULL
              AND expires_at > #{now}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    UploadSession selectActiveSession(
            @Param("userId") Long userId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("logicalDocumentId") Long logicalDocumentId,
            @Param("fileHash") String fileHash,
            @Param("now") LocalDateTime now
    );

    @Update("UPDATE upload_sessions SET status=#{status}, error_message=NULL, updated_at=#{now} WHERE id=#{id}")
    int setPhase(@Param("id") Long id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("UPDATE upload_sessions SET error_message=#{error}, updated_at=#{now} WHERE id=#{id}")
    int setError(@Param("id") Long id, @Param("error") String error, @Param("now") LocalDateTime now);
}
