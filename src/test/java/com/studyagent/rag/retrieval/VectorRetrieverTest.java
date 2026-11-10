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

class VectorRetrieverTest {
    private static final List<String> ACTIVE_DOCUMENT_IDS = List.of("42");

    private ElasticsearchClient client;
    private VectorRetriever retriever;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        retriever = new VectorRetriever(client, properties());
    }

    @Test
    void searchesCosineIndexWithinServerProvidedKnowledgeBaseAndMapsProvenance() throws IOException {
        ElasticsearchChunkDocument document = document("chunk-9", "parent-2", "接口引用可以指向不同实现对象");
        SearchResponse<ElasticsearchChunkDocument> response = responseWith(Hit.of(hit -> hit
                .index("chunks-v1")
                .id("chunk-9")
                .score(0.92)
                .source(document)));
        when(client.search(any(SearchRequest.class), eq(ElasticsearchChunkDocument.class)))
                .thenReturn(response);

        List<RetrievalHit> hits = retriever.retrieve(
                "user-4",
                "kb-7",
                new float[]{0.1f, 0.2f, 0.3f},
                2,
                ACTIVE_DOCUMENT_IDS
        );

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(requestCaptor.capture(), eq(ElasticsearchChunkDocument.class));
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.index()).containsExactly("chunks-v1-read");
        assertThat(request.size()).isEqualTo(2);
        assertThat(request.knn()).hasSize(1);
        assertThat(request.knn().getFirst().field()).isEqualTo("embedding");
        assertThat(request.knn().getFirst().queryVector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(request.knn().getFirst().k()).isEqualTo(2);
        assertThat(request.knn().getFirst().filter()).hasSize(4);
        assertThat(request.knn().getFirst().filter().get(0).term().field()).isEqualTo("user_id");
        assertThat(request.knn().getFirst().filter().get(0).term().value().stringValue())
                .isEqualTo("user-4");
        assertThat(request.knn().getFirst().filter().get(1).term().field())
                .isEqualTo("knowledge_base_id");
        assertThat(request.knn().getFirst().filter().get(1).term().value().stringValue())
                .isEqualTo("kb-7");
        assertThat(request.knn().getFirst().filter().get(2).term().field()).isEqualTo("chunk_type");
        assertThat(request.knn().getFirst().filter().get(2).term().value().stringValue())
                .isEqualTo("CHILD");
        var documents = request.knn().getFirst().filter().get(3).terms();
        assertThat(documents.field()).isEqualTo("document_id");
        assertThat(documents.terms().value().getFirst().stringValue()).isEqualTo("42");
        assertThat(hits).containsExactly(new RetrievalHit(
                "chunk-9",
                "parent-2",
                "接口引用可以指向不同实现对象",
                new RetrievalHit.Provenance("doc-1", "demo.pdf", "{\"startOffset\":1}"),
                0.92,
                RetrievalStrategy.VECTOR
        ));
    }

    @Test
    void rejectsWrongVectorDimensionsBeforeCallingElasticsearch() throws IOException {
        assertThatThrownBy(() -> retriever.retrieve("user-4", "kb-7", new float[]{0.1f, 0.2f}, 2, ACTIVE_DOCUMENT_IDS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("actual=2, expected=3");

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
