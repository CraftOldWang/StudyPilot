package com.studyagent.rag.retrieval;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.rag.index.ElasticsearchChunkDocument;

final class RetrievalHitMapper {

    private RetrievalHitMapper() {
    }

    static RetrievalHit map(
            Hit<ElasticsearchChunkDocument> hit,
            RetrievalStrategy strategy
    ) {
        ElasticsearchChunkDocument source = hit.source();
        if (source == null) {
            throw new BusinessException("Elasticsearch 检索命中缺少 _source: id=" + hit.id());
        }
        RetrievalHit.Position position = source.chunkerVersion() == null || source.createdAt() == null
                || source.chunkIndex() == null ? null
                : new RetrievalHit.Position(hit.index(), source.chunkerVersion(), source.createdAt(), source.chunkIndex());
        return new RetrievalHit(
                source.chunkId(),
                source.parentChunkId(),
                source.content(),
                new RetrievalHit.Provenance(
                        source.documentId(), source.documentTitle(), source.sourceLocation()),
                hit.score() == null ? 0.0 : hit.score(),
                strategy,
                position
        );
    }
}
