package com.studyagent.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 批量读取 child 命中所指向的 parent 内容，同时保留 child 的召回证据。
 */
@Component
@RequiredArgsConstructor
public class ParentAggregator {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public List<KnowledgeSearchResponse.Result> aggregate(
            String userId,
            String knowledgeBaseId,
            List<RetrievalHit> childHits
    ) {
        validateScope(userId, knowledgeBaseId);
        if (childHits == null) {
            throw new BusinessException("child 命中不能为空");
        }
        List<String> parentChunkIds = childHits.stream()
                .map(RetrievalHit::parentChunkId)
                .filter(Objects::nonNull)
                .filter(parentChunkId -> !parentChunkId.isBlank())
                .distinct()
                .toList();
        if (parentChunkIds.isEmpty()) {
            return childHits.stream().map(this::childResult).toList();
        }

        SearchRequest request = parentSearchRequest(userId, knowledgeBaseId, parentChunkIds);
        try {
            SearchResponse<ElasticsearchChunkDocument> response =
                    client.search(request, ElasticsearchChunkDocument.class);
            Map<String, ElasticsearchChunkDocument> parents = parentContent(response.hits().hits());
            Map<String, KnowledgeSearchResponse.Result> contexts = new LinkedHashMap<>();
            for (RetrievalHit child : childHits) {
                if (child.parentChunkId() == null || child.parentChunkId().isBlank()) {
                    contexts.putIfAbsent(child.chunkId(), childResult(child));
                    continue;
                }
                ElasticsearchChunkDocument parent = parents.get(child.parentChunkId());
                if (parent == null) {
                    throw new BusinessException("命中子块对应的 parent 缺失: " + child.parentChunkId());
                }
                if (!Objects.equals(child.provenance().documentId(), parent.documentId())) {
                    throw new BusinessException("命中子块对应的 parent 属于其他文档版本: " + child.parentChunkId());
                }
                contexts.putIfAbsent(parent.chunkId(), new KnowledgeSearchResponse.Result(
                        parent.chunkId(), parent.content(), new RetrievalHit.Provenance(
                                parent.documentId(), parent.documentTitle(), parent.sourceLocation()), child.score()));
            }
            return List.copyOf(contexts.values());
        } catch (IOException ex) {
            throw new BusinessException("Elasticsearch parent chunk 检索失败: " + ex.getMessage());
        }
    }

    private SearchRequest parentSearchRequest(
            String userId,
            String knowledgeBaseId,
            List<String> parentChunkIds
    ) {
        List<FieldValue> parentIds = parentChunkIds.stream().map(FieldValue::of).toList();
        return SearchRequest.of(search -> search
                .index(properties.readAlias())
                .size(parentChunkIds.size())
                .query(query -> query.bool(bool -> bool
                        .filter(filter -> filter.term(term -> term
                                .field("user_id")
                                .value(userId)))
                        .filter(filter -> filter.term(term -> term
                                .field("knowledge_base_id")
                                .value(knowledgeBaseId)))
                        .filter(filter -> filter.term(term -> term
                                .field("chunk_type")
                                .value("PARENT")))
                        .filter(filter -> filter.terms(terms -> terms
                                .field("chunk_id")
                                .terms(values -> values.value(parentIds)))))));
    }

    private Map<String, ElasticsearchChunkDocument> parentContent(List<Hit<ElasticsearchChunkDocument>> parentHits) {
        Map<String, ElasticsearchChunkDocument> contentById = new LinkedHashMap<>();
        for (Hit<ElasticsearchChunkDocument> parentHit : parentHits) {
            ElasticsearchChunkDocument source = parentHit.source();
            if (source == null) {
                throw new BusinessException("Elasticsearch parent 命中缺少 _source: id=" + parentHit.id());
            }
            contentById.put(source.chunkId(), source);
        }
        return contentById;
    }

    private KnowledgeSearchResponse.Result childResult(RetrievalHit child) {
        return new KnowledgeSearchResponse.Result(child.chunkId(), child.content(), child.provenance(), child.score());
    }

    private void validateScope(String userId, String knowledgeBaseId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("用户范围不能为空");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new BusinessException("知识库范围不能为空");
        }
    }
}
