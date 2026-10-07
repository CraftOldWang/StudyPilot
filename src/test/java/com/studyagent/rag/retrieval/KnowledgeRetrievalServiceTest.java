package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.studyagent.config.RagProperties;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalServiceTest {
    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final ParentAggregator parents = mock(ParentAggregator.class);
    private final RagProperties properties = new RagProperties(2, 800, 80, 2400, 0, 30, 20, 60,
            RagProperties.ChunkStrategy.STRUCTURED, 2400);
    private final KnowledgeRetrievalService service = new KnowledgeRetrievalService(
            embedding, retrieval, properties, parents, String::length);

    @Test
    void keepsOriginalChildRankAndUsesParentSourceCoordinates() {
        float[] vector = {0.1f, 0.2f};
        var childSource = new RetrievalHit.Provenance("doc", "Java", "child-location");
        var parentSource = new RetrievalHit.Provenance("doc", "Java", "parent-location");
        var child = new RetrievalHit("child", "parent", "child body", childSource, 0.9, RetrievalStrategy.RRF);
        when(embedding.embed("Java", EmbeddingPurpose.QUERY)).thenReturn(vector);
        when(retrieval.retrieve(RetrievalMode.PARENT, "11", "22", "Java", vector, 30, 20, 2, 60))
                .thenReturn(List.of(child));
        when(parents.aggregate("11", "22", List.of(child))).thenReturn(List.of(
                new KnowledgeSearchResponse.Result("parent", "parent body", parentSource, 0.9)));
        var response = service.search(11L, 22L, "  Java  ", RetrievalMode.PARENT, null);
        assertThat(response.query()).isEqualTo("Java");
        assertThat(response.rankedChildren()).containsExactly(child);
        assertThat(response.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.chunkId()).isEqualTo("parent");
            assertThat(hit.provenance()).isEqualTo(parentSource);
        });
        assertThat(response.contextMatches().getFirst().matchedChildren()).singleElement()
                .satisfies(evidence -> assertThat(evidence.provenance()).isEqualTo(childSource));
        assertThat(response.contextTokens()).isEqualTo("parent body".length());
        verify(retrieval).retrieve(RetrievalMode.PARENT, "11", "22", "Java", vector, 30, 20, 2, 60);
    }

    @Test
    void bm25SkipsEmbeddingAndBudgetDoesNotChangeRecordedRanking() {
        var first = new RetrievalHit("a", "pa", "a".repeat(1600), null, 3, RetrievalStrategy.BM25);
        var second = new RetrievalHit("b", "pb", "b".repeat(1200), null, 2, RetrievalStrategy.BM25);
        var third = new RetrievalHit("c", "pc", "c".repeat(500), null, 1, RetrievalStrategy.BM25);
        when(retrieval.retrieve(RetrievalMode.BM25, "11", "22", "query", null, 30, 20, 3, 60))
                .thenReturn(List.of(first, second, third));
        var response = service.search(11L, 22L, "query", RetrievalMode.BM25, 3);
        assertThat(response.rankedChildren()).containsExactly(first, second, third);
        assertThat(response.hits()).extracting(KnowledgeSearchResponse.Result::chunkId).containsExactly("a", "c");
        assertThat(response.contextTokens()).isEqualTo(2100);
        verifyNoInteractions(embedding, parents);
    }

    @Test
    void emptyEvidenceRemainsExplicit() {
        var response = service.search(11L, 22L, "query");
        assertThat(response.message()).isEqualTo(KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE);
        assertThat(response.hits()).isEmpty();
    }

    @Test
    void agentAndUnspecifiedApiModeUseVectorChildContext() {
        float[] vector = {0.1f, 0.2f};
        var child = new RetrievalHit("child", "parent", "child body", null, 0.9, RetrievalStrategy.VECTOR);
        when(embedding.embed("query", EmbeddingPurpose.QUERY)).thenReturn(vector);
        when(retrieval.retrieve(RetrievalMode.VECTOR, "11", "22", "query", vector, 30, 20, 2, 60))
                .thenReturn(List.of(child));
        var agent = service.search(11L, 22L, "query");
        var api = service.search(11L, 22L, "query", null, null);
        for (var response : List.of(agent, api)) {
            assertThat(response.mode()).isEqualTo(RetrievalMode.VECTOR);
            assertThat(response.hits()).extracting(KnowledgeSearchResponse.Result::chunkId).containsExactly("child");
        }
        verifyNoInteractions(parents);
    }

    @Test
    void comparisonUsesOneRetrievalAndTheSameChildRanksForBothContextViews() {
        float[] vector = {0.1f, 0.2f};
        var source = new RetrievalHit.Provenance("doc", "Title", "location");
        var first = new RetrievalHit("c1", "p", "a".repeat(1500), source, 0.9, RetrievalStrategy.RRF);
        var second = new RetrievalHit("c2", "p", "b".repeat(1200), source, 0.8, RetrievalStrategy.RRF);
        when(embedding.embed("query", EmbeddingPurpose.QUERY)).thenReturn(vector);
        when(retrieval.retrieve(RetrievalMode.RRF, "11", "22", "query", vector, 30, 20, 2, 60))
                .thenReturn(List.of(first, second));
        when(parents.aggregate("11", "22", List.of(first, second))).thenReturn(List.of(
                new KnowledgeSearchResponse.Result("p", "p".repeat(2300), source, 0.9)));
        var result = service.compareContexts(11L, 22L, "query", 2);
        assertThat(result.childContext().rankedChildren()).containsExactly(first, second);
        assertThat(result.parentContext().rankedChildren()).isSameAs(result.childContext().rankedChildren());
        assertThat(result.childContext().hits()).extracting(KnowledgeSearchResponse.Result::chunkId).containsExactly("c1");
        assertThat(result.parentContext().hits()).extracting(KnowledgeSearchResponse.Result::chunkId).containsExactly("p");
        assertThat(result.childContext().contextTokens()).isEqualTo(1500);
        assertThat(result.parentContext().contextTokens()).isEqualTo(2300);
        verify(embedding, times(1)).embed("query", EmbeddingPurpose.QUERY);
        verify(retrieval, times(1)).retrieve(RetrievalMode.RRF, "11", "22", "query", vector, 30, 20, 2, 60);
        verifyNoMoreInteractions(retrieval, embedding);
    }

    @Test
    void invalidTopKIsRejectedBeforeProviderUsage() {
        assertThatThrownBy(() -> service.search(11L, 22L, "query", RetrievalMode.RRF, 21))
                .hasMessageContaining("topK");
        verifyNoInteractions(embedding, retrieval, parents);
    }
}
