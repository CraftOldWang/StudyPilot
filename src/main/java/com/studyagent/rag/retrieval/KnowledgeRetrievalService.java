package com.studyagent.rag.retrieval;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.util.List;
import java.util.ArrayList;
import com.studyagent.algo.chunk.TokenCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 权限范围内的课程检索入口，默认使用向量检索，保留其他策略用于对照。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final RagProperties ragProperties;
    private final ParentAggregator parentAggregator;
    private final TokenCounter tokenCounter;

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query) {
        return search(userId, knowledgeBaseId, query, RetrievalMode.VECTOR, ragProperties.topK());
    }

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query, RetrievalMode mode, Integer topK) {
        if (userId == null || knowledgeBaseId == null) {
            throw new BusinessException("检索 scope 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索问题不能为空");
        }
        String normalizedQuery = query.trim();
        RetrievalMode effectiveMode = mode == null ? RetrievalMode.VECTOR : mode;
        int limit = topK == null ? ragProperties.topK() : topK;
        if (limit <= 0 || limit > 20) { throw new BusinessException("topK 必须在 1 到 20 之间"); }
        float[] queryVector = effectiveMode == RetrievalMode.BM25 ? null
                : embeddingService.embed(normalizedQuery, EmbeddingPurpose.QUERY);
        List<RetrievalHit> rankedChildren = retrievalService.retrieve(
                        effectiveMode,
                        userId.toString(),
                        knowledgeBaseId.toString(),
                        normalizedQuery,
                        queryVector,
                        ragProperties.bm25CandidateSize(), ragProperties.vectorCandidateSize(), limit, ragProperties.rrfK());
        return contextView(userId, knowledgeBaseId, normalizedQuery, effectiveMode, rankedChildren);
    }

    public ContextComparison compareContexts(Long userId, Long knowledgeBaseId, String query, Integer topK) {
        KnowledgeSearchResponse child = search(userId, knowledgeBaseId, query, RetrievalMode.RRF, topK);
        // Independent provider/search calls can reorder candidates; an ablation must share the exact retrieval result.
        KnowledgeSearchResponse parent = contextView(userId, knowledgeBaseId, child.query(), RetrievalMode.PARENT, child.rankedChildren());
        return new ContextComparison(child, parent);
    }

    private KnowledgeSearchResponse contextView(Long userId, Long knowledgeBaseId, String normalizedQuery,
                                                RetrievalMode effectiveMode, List<RetrievalHit> rankedChildren) {
        List<KnowledgeSearchResponse.Result> candidates = effectiveMode == RetrievalMode.PARENT
                ? parentAggregator.aggregate(userId.toString(), knowledgeBaseId.toString(), rankedChildren)
                : rankedChildren.stream().map(hit -> new KnowledgeSearchResponse.Result(
                        hit.chunkId(), hit.content(), hit.provenance(), hit.score())).toList();
        List<KnowledgeSearchResponse.Result> results = new ArrayList<>();
        List<KnowledgeSearchResponse.ContextMatch> matches = new ArrayList<>();
        int contextTokens = 0;
        for (var context : candidates) {
            int size = tokenCounter.count(context.content());
            // Preserve complete source chunks; later smaller contexts may still fit the same text budget.
            if (contextTokens + size > ragProperties.contextMaxTokens()) { continue; }
            results.add(context);
            contextTokens += size;
            var evidence = rankedChildren.stream().filter(child -> context.chunkId().equals(child.chunkId())
                            || (effectiveMode == RetrievalMode.PARENT && context.chunkId().equals(child.parentChunkId())))
                    .map(child -> new KnowledgeSearchResponse.ChildEvidence(child.chunkId(), child.provenance(), child.score())).toList();
            matches.add(new KnowledgeSearchResponse.ContextMatch(context.chunkId(), evidence));
        }
        return new KnowledgeSearchResponse(
                normalizedQuery,
                results.isEmpty() ? KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE : null,
                List.copyOf(results), rankedChildren, List.copyOf(matches), contextTokens, effectiveMode);
    }

    public record ContextComparison(KnowledgeSearchResponse childContext, KnowledgeSearchResponse parentContext) { }
}
