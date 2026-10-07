package com.studyagent.rag.retrieval;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        String message,
        List<Result> hits,
        List<RetrievalHit> rankedChildren,
        List<ContextMatch> contextMatches,
        int contextTokens,
        RetrievalMode mode
) {

    public KnowledgeSearchResponse(String query, String message, List<Result> hits) {
        this(query, message, hits, List.of(), List.of(), 0, RetrievalMode.VECTOR);
    }

    // Evaluation retains raw child ranks; the model receives each admitted context body only once.
    public ModelView modelView() { return new ModelView(query, message, hits, contextMatches, contextTokens); }

    public record ModelView(String query, String message, List<Result> hits,
                            List<ContextMatch> contextMatches, int contextTokens) { }

    public record ChildEvidence(String chunkId, RetrievalHit.Provenance provenance, double score) { }
    public record ContextMatch(String contextChunkId, List<ChildEvidence> matchedChildren) { }

    public static final String NO_EVIDENCE_MESSAGE = "当前知识库没有可支持该问题的资料依据。";

    public record Result(
            String chunkId,
            String content,
            RetrievalHit.Provenance provenance,
            double score
    ) {
    }
}
