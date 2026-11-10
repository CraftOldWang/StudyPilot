package com.studyagent.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 在服务端指定的用户和知识库范围内执行 BM25 检索。
 */
@Component
@RequiredArgsConstructor
public class BM25Retriever {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public List<RetrievalHit> retrieve(String userId, String knowledgeBaseId, String query, int candidateLimit,
                                       List<String> activeDocumentIds) {
        validateRequest(userId, knowledgeBaseId, query, candidateLimit);
        if (activeDocumentIds.isEmpty()) {
            return List.of();
        }
        var documentIds = activeDocumentIds.stream().map(FieldValue::of).toList();
        SearchRequest request = SearchRequest.of(search -> search
                .index(properties.readAlias())
                .size(candidateLimit)
                .query(q -> q.bool(bool -> bool
                        .must(must -> must.match(match -> match
                                .field("content")
                                .query(query)))
                        .filter(filter -> filter.term(term -> term
                                .field("user_id")
                                .value(userId)))
                        .filter(filter -> filter.term(term -> term
                                .field("knowledge_base_id")
                                .value(knowledgeBaseId)))
                        .filter(filter -> filter.term(term -> term
                                .field("chunk_type")
                                .value("CHILD")))
                        .filter(filter -> filter.terms(terms -> terms.field("document_id")
                                .terms(values -> values.value(documentIds))))))
                .sort(sort -> sort.score(score -> score.order(SortOrder.Desc))));
        try {
            SearchResponse<ElasticsearchChunkDocument> response =
                    client.search(request, ElasticsearchChunkDocument.class);
            return response.hits().hits().stream()
                    .map(hit -> RetrievalHitMapper.map(hit, RetrievalStrategy.BM25))
                    .toList();
        } catch (IOException ex) {
            throw new BusinessException("Elasticsearch BM25 检索失败: " + ex.getMessage());
        }
    }

    private void validateRequest(String userId, String knowledgeBaseId, String query, int candidateLimit) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("用户范围不能为空");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new BusinessException("知识库范围不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索文本不能为空");
        }
        if (candidateLimit <= 0) {
            throw new BusinessException("候选数量必须大于 0");
        }
    }
}
