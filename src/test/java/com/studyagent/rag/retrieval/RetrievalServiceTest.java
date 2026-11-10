package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
    private static final List<String> ACTIVE_DOCUMENT_IDS = List.of("42");

    private BM25Retriever bm25Retriever;
    private VectorRetriever vectorRetriever;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        bm25Retriever = mock(BM25Retriever.class);
        vectorRetriever = mock(VectorRetriever.class);
        service = new RetrievalService(bm25Retriever, vectorRetriever);
    }

    @Test
    void bm25ModeUsesCandidateLimitAndAppliesTopKInOrchestration() {
        List<RetrievalHit> candidates = List.of(hit("a", RetrievalStrategy.BM25, 3.0),
                hit("b", RetrievalStrategy.BM25, 2.0), hit("c", RetrievalStrategy.BM25, 1.0));
        when(bm25Retriever.retrieve("user-4", "kb-7", "多态", 6, ACTIVE_DOCUMENT_IDS)).thenReturn(candidates);

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.BM25, "user-4", "kb-7", "多态", null, 6, 6, 2, 60, ACTIVE_DOCUMENT_IDS);

        assertThat(result).containsExactly(candidates.get(0), candidates.get(1));
        verify(vectorRetriever, never()).retrieve("user-4", "kb-7", null, 6, ACTIVE_DOCUMENT_IDS);
    }

    @Test
    void vectorModeUsesCandidateLimitAndAppliesTopKInOrchestration() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<RetrievalHit> candidates = List.of(hit("a", RetrievalStrategy.VECTOR, 0.9),
                hit("b", RetrievalStrategy.VECTOR, 0.8));
        when(vectorRetriever.retrieve("user-4", "kb-7", vector, 5, ACTIVE_DOCUMENT_IDS)).thenReturn(candidates);

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.VECTOR, "user-4", "kb-7", null, vector, 6, 5, 1, 60, ACTIVE_DOCUMENT_IDS);

        assertThat(result).containsExactly(candidates.getFirst());
        verify(bm25Retriever, never()).retrieve("user-4", "kb-7", null, 5, ACTIVE_DOCUMENT_IDS);
    }

    @Test
    void rrfModeUsesRankConstantSixtyAndReturnsUnifiedHits() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(bm25Retriever.retrieve("user-4", "kb-7", "多态", 6, ACTIVE_DOCUMENT_IDS)).thenReturn(List.of(
                hit("a", RetrievalStrategy.BM25, 7.0),
                hit("b", RetrievalStrategy.BM25, 6.0)
        ));
        when(vectorRetriever.retrieve("user-4", "kb-7", vector, 6, ACTIVE_DOCUMENT_IDS)).thenReturn(List.of(
                hit("b", RetrievalStrategy.VECTOR, 0.95),
                hit("c", RetrievalStrategy.VECTOR, 0.90)
        ));

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.RRF, "user-4", "kb-7", "多态", vector, 6, 6, 3, 60, ACTIVE_DOCUMENT_IDS);

        assertThat(result).extracting(RetrievalHit::chunkId).containsExactly("b", "a", "c");
        assertThat(result).extracting(RetrievalHit::strategy)
                .containsOnly(RetrievalStrategy.RRF);
        assertThat(result.getFirst().score()).isEqualTo(1.0 / 62.0 + 1.0 / 61.0);
    }

    @Test
    void parentModeKeepsExactlyTheSameTopKChildRanksAsRrf() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(bm25Retriever.retrieve("user-4", "kb-7", "query", 6, ACTIVE_DOCUMENT_IDS))
                .thenReturn(List.of(hit("a", RetrievalStrategy.BM25, 2.0)));
        when(vectorRetriever.retrieve("user-4", "kb-7", vector, 9, ACTIVE_DOCUMENT_IDS))
                .thenReturn(List.of(hit("b", RetrievalStrategy.VECTOR, 0.8)));
        var parent = service.retrieve(RetrievalMode.PARENT, "user-4", "kb-7", "query", vector, 6, 9, 1, 10, ACTIVE_DOCUMENT_IDS);
        var rrf = service.retrieve(RetrievalMode.RRF, "user-4", "kb-7", "query", vector, 6, 9, 1, 10, ACTIVE_DOCUMENT_IDS);
        assertThat(parent).containsExactlyElementsOf(rrf).hasSize(1);
        assertThat(parent.getFirst().score()).isEqualTo(1.0 / 11.0);
        assertThat(parent.getFirst().content()).startsWith("content ");
    }

    private RetrievalHit hit(String chunkId, RetrievalStrategy strategy, double score) {
        return new RetrievalHit(chunkId, "parent-" + chunkId, "content " + chunkId, null, score, strategy);
    }
}
