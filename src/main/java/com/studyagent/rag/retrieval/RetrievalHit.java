package com.studyagent.rag.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

/**
 * 单策略检索的最小统一命中，供后续融合和父块聚合保留召回证据。
 */
public record RetrievalHit(
        String chunkId,
        String parentChunkId,
        String content,
        Provenance provenance,
        double score,
        RetrievalStrategy strategy,
        @JsonIgnore Position position
) {

    public RetrievalHit(String chunkId, String parentChunkId, String content, Provenance provenance,
                        double score, RetrievalStrategy strategy) {
        this(chunkId, parentChunkId, content, provenance, score, strategy, null);
    }

    public record Position(String index, String chunkerVersion, LocalDateTime createdAt, Integer ordinal) { }

    public record Provenance(
            String documentId,
            String documentTitle,
            String sourceLocation
    ) {
    }
}
