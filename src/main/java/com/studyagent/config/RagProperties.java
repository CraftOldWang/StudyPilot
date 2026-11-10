package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 检索、切块和父子检索配置。
 *
 * <p>父子 size 与 overlap 均使用 CL100K_BASE token。size 是每块上限。
 * overlap 是同级相邻窗口共享文本的上限，保留整段或句子时实际重叠可以更短。</p>
 */
@ConfigurationProperties(prefix = "study-agent.rag")
public record RagProperties(
        int topK,
        int chunkSize,
        int chunkOverlap,
        int parentChunkSize,
        int parentChunkOverlap,
        int bm25CandidateSize,
        int vectorCandidateSize,
        int rrfK,
        @DefaultValue("STRUCTURED") ChunkStrategy chunkStrategy,
        @DefaultValue("4096") int contextMaxTokens
) {
    public enum ChunkStrategy { STRUCTURED, FIXED, RECURSIVE }

    public RagProperties {
        if (chunkSize <= 0 || chunkOverlap < 0 || chunkOverlap >= chunkSize
                || parentChunkSize < chunkSize || parentChunkOverlap < 0 || parentChunkOverlap >= parentChunkSize) {
            throw new IllegalArgumentException("Invalid child/parent token window configuration");
        }
        if (chunkStrategy == null || (chunkStrategy == ChunkStrategy.STRUCTURED && parentChunkOverlap != 0)) {
            throw new IllegalArgumentException("Structured parents require zero overlap");
        }
        if (topK <= 0 || topK > 20 || bm25CandidateSize <= 0 || vectorCandidateSize <= 0 || rrfK <= 0
                || contextMaxTokens < parentChunkSize) {
            throw new IllegalArgumentException("Invalid retrieval limits or context budget smaller than one parent");
        }
    }
}
