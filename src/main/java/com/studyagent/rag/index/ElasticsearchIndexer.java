package com.studyagent.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 使用官方 Java API Client 写入 chunks-v1-write。
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchIndexer {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public String index(ElasticsearchChunkDocument document) {
        validateDimensions(document);
        IndexRequest<ElasticsearchChunkDocument> request = IndexRequest.of(i -> i
                .index(properties.writeAlias())
                .id(document.chunkId())
                .document(document));
        try {
            IndexResponse response = client.index(request);
            return response.id();
        } catch (IOException ex) {
            throw new BusinessException("写入 Elasticsearch chunk 失败: chunkId="
                    + document.chunkId() + ", error=" + ex.getMessage());
        }
    }

    public void bulkIndex(List<ElasticsearchChunkDocument> documents) {
        BulkIndexResult result = bulkIndexAcknowledged(documents);
        if (!result.failures().isEmpty()) {
            throw new BusinessException("批量写入 Elasticsearch chunk 失败: " + result.failures());
        }
    }

    public BulkIndexResult bulkIndexAcknowledged(List<ElasticsearchChunkDocument> documents) {
        if (documents.isEmpty()) {
            return new BulkIndexResult(List.of(), List.of());
        }
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (ElasticsearchChunkDocument document : documents) {
            validateDimensions(document);
            request.operations(operation -> operation.index(index -> index
                    .index(properties.writeAlias())
                    .id(document.chunkId())
                    .document(document)));
        }
        try {
            BulkResponse response = client.bulk(request.build());
            if (response.items().size() != documents.size()) {
                throw new BusinessException("Elasticsearch bulk 确认数量与请求不一致");
            }
            java.util.ArrayList<String> succeeded = new java.util.ArrayList<>();
            java.util.ArrayList<String> failures = new java.util.ArrayList<>();
            for (int index = 0; index < documents.size(); index++) {
                var item = response.items().get(index);
                String chunkId = documents.get(index).chunkId();
                if (!chunkId.equals(item.id())) {
                    throw new BusinessException("Elasticsearch bulk 确认 ID 与请求不一致");
                }
                if (item.error() == null && item.status() >= 200 && item.status() < 300) {
                    succeeded.add(chunkId);
                } else {
                    failures.add(chunkId + ": " + (item.error() == null ? item.status() : item.error().reason()));
                }
            }
            if (response.errors() && failures.isEmpty()) {
                throw new BusinessException("Elasticsearch bulk 错误标识与逐项结果不一致");
            }
            return new BulkIndexResult(List.copyOf(succeeded), List.copyOf(failures));
        } catch (IOException ex) {
            throw new BusinessException("批量写入 Elasticsearch chunk 请求失败: " + ex.getMessage());
        }
    }

    public record BulkIndexResult(List<String> succeededIds, List<String> failures) { }

    public void refresh() {
        try {
            var response = client.indices().refresh(request -> request.index(properties.physicalIndex()));
            if (response.shards().failed().longValue() > 0) {
                throw new BusinessException("Elasticsearch 索引刷新失败: " + response.shards().failures());
            }
        } catch (IOException ex) {
            throw new BusinessException("Elasticsearch 索引刷新请求失败: " + ex.getMessage());
        }
    }

    private void validateDimensions(ElasticsearchChunkDocument document) {
        if (document.userId() == null || document.userId().isBlank()) {
            throw new BusinessException("Elasticsearch chunk 缺少服务端 userId: chunkId=" + document.chunkId());
        }
        if ("PARENT".equals(document.chunkType()) && document.embedding() == null) { return; }
        if (document.embedding() == null || document.embedding().length != properties.vectorDimensions()) {
            throw new BusinessException("Embedding 维度与 Elasticsearch 配置不一致: actual="
                    + (document.embedding() == null ? "null" : document.embedding().length)
                    + ", expected=" + properties.vectorDimensions());
        }
    }
}
