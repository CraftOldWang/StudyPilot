package com.studyagent.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
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
 * 在服务端指定的用户和知识库范围内执行 cosine kNN 检索。
 */
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public List<RetrievalHit> retrieve(
            String userId,
            String knowledgeBaseId,
            float[] queryVector,
            int candidateLimit,
            List<String> activeDocumentIds
    ) {
        validateRequest(userId, knowledgeBaseId, queryVector, candidateLimit);
        if (activeDocumentIds.isEmpty()) {
            return List.of();
        }
        var documentIds = activeDocumentIds.stream().map(FieldValue::of).toList();
        List<Float> vector = toBoxedVector(queryVector);
        SearchRequest request = SearchRequest.of(search -> search
                .index(properties.readAlias())
                .size(candidateLimit)
                .knn(knn -> knn
                        .field("embedding")
                        .queryVector(vector)
                        .k(candidateLimit)
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
                                .terms(values -> values.value(documentIds))))));
        try {
            SearchResponse<ElasticsearchChunkDocument> response =
                    client.search(request, ElasticsearchChunkDocument.class);
            return response.hits().hits().stream()
                    .map(hit -> RetrievalHitMapper.map(hit, RetrievalStrategy.VECTOR))
                    .toList();
        } catch (IOException ex) {
            throw new BusinessException("Elasticsearch 向量检索失败: " + ex.getMessage());
        }
    }

    private List<Float> toBoxedVector(float[] queryVector) {
        List<Float> vector = new java.util.ArrayList<>(queryVector.length);
        for (float value : queryVector) {
            vector.add(value);
        }
        return vector;
    }

    private void validateRequest(String userId, String knowledgeBaseId, float[] queryVector, int candidateLimit) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("用户范围不能为空");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new BusinessException("知识库范围不能为空");
        }
        if (queryVector == null || queryVector.length != properties.vectorDimensions()) {
            int actualDimensions = queryVector == null ? 0 : queryVector.length;
            throw new BusinessException("查询向量维度与 Elasticsearch 配置不一致: actual="
                    + actualDimensions + ", expected=" + properties.vectorDimensions());
        }
        if (candidateLimit <= 0) {
            throw new BusinessException("候选数量必须大于 0");
        }
    }
}
