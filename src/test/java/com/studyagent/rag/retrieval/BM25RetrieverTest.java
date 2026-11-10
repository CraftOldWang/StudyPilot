package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BM25RetrieverTest {
    private static final List<String> ACTIVE_DOCUMENT_IDS = List.of("42");

    private ElasticsearchClient client;
    private BM25Retriever retriever;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        retriever = new BM25Retriever(client, properties());
    }

    @Test
    void searchesContentWithinServerProvidedKnowledgeBaseAndMapsProvenance() throws IOException {
        ElasticsearchChunkDocument document = document("chunk-11", "parent-3", "多态允许统一接口表示不同实现");
        SearchResponse<ElasticsearchChunkDocument> response = responseWith(Hit.of(hit -> hit
                .index("chunks-v1")
                .id("chunk-11")
                .score(3.25)
                .source(document)));
        when(client.search(any(SearchRequest.class), eq(ElasticsearchChunkDocument.class)))
                .thenReturn(response);

        List<RetrievalHit> hits = retriever.retrieve("user-4", "kb-7", "什么是多态", 3, ACTIVE_DOCUMENT_IDS);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(requestCaptor.capture(), eq(ElasticsearchChunkDocument.class));
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.index()).containsExactly("chunks-v1-read");
        assertThat(request.size()).isEqualTo(3);
        assertThat(request.query().bool().must().getFirst().match().field()).isEqualTo("content");
        assertThat(request.query().bool().must().getFirst().match().query().stringValue())
                .isEqualTo("什么是多态");
        assertThat(request.query().bool().filter()).hasSize(4);
        assertThat(request.query().bool().filter().get(0).term().field()).isEqualTo("user_id");
        assertThat(request.query().bool().filter().get(0).term().value().stringValue())
                .isEqualTo("user-4");
        assertThat(request.query().bool().filter().get(1).term().field())
                .isEqualTo("knowledge_base_id");
        assertThat(request.query().bool().filter().get(1).term().value().stringValue())
                .isEqualTo("kb-7");
        assertThat(request.query().bool().filter().get(2).term().field()).isEqualTo("chunk_type");
        assertThat(request.query().bool().filter().get(2).term().value().stringValue())
                .isEqualTo("CHILD");
        var documents = request.query().bool().filter().get(3).terms();
        assertThat(documents.field()).isEqualTo("document_id");
        assertThat(documents.terms().value().getFirst().stringValue()).isEqualTo("42");
        assertThat(request.sort().getFirst().score().order()).isEqualTo(SortOrder.Desc);
        assertThat(hits).containsExactly(new RetrievalHit(
                "chunk-11",
                "parent-3",
                "多态允许统一接口表示不同实现",
                new RetrievalHit.Provenance("doc-1", "demo.pdf", "{\"startOffset\":1}"),
                3.25,
                RetrievalStrategy.BM25
        ));
    }

    @Test
    void rejectsMissingIsolationScopeBeforeCallingElasticsearch() throws IOException {
        assertThatThrownBy(() -> retriever.retrieve(" ", "kb-7", "多态", 3, ACTIVE_DOCUMENT_IDS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户范围不能为空");
        assertThatThrownBy(() -> retriever.retrieve("user-4", " ", "多态", 3, ACTIVE_DOCUMENT_IDS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("知识库范围不能为空");

        verify(client, never()).search(any(SearchRequest.class), eq(ElasticsearchChunkDocument.class));
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<ElasticsearchChunkDocument> responseWith(Hit<ElasticsearchChunkDocument> hit) {
        SearchResponse<ElasticsearchChunkDocument> response = mock(SearchResponse.class);
        HitsMetadata<ElasticsearchChunkDocument> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);
        when(hits.hits()).thenReturn(List.of(hit));
        return response;
    }

    private ElasticsearchProperties properties() {
        return new ElasticsearchProperties(
                "http://localhost:9200",
                "chunks-v1",
                "chunks-v1-read",
                "chunks-v1-write",
                3
        );
    }

    private ElasticsearchChunkDocument document(String chunkId, String parentChunkId, String content) {
        ElasticsearchChunkDocument document = mock(ElasticsearchChunkDocument.class);
        when(document.chunkId()).thenReturn(chunkId);
        when(document.parentChunkId()).thenReturn(parentChunkId);
        when(document.content()).thenReturn(content);
        when(document.documentId()).thenReturn("doc-1");
        when(document.documentTitle()).thenReturn("demo.pdf");
        when(document.sourceLocation()).thenReturn("{\"startOffset\":1}");
        return document;
    }
}
