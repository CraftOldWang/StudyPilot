package com.studyagent.rag.retrieval;

import com.studyagent.algo.chunk.TokenCounter;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
import com.studyagent.config.RerankProperties;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import com.studyagent.rag.rerank.BgeReranker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
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
    private final BgeReranker reranker;
    private final RerankProperties rerankProperties;
    private final ContextExpansion contextExpansion;

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query) {
        return search(userId, knowledgeBaseId, query, RetrievalMode.VECTOR, ragProperties.topK());
    }

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query, RetrievalMode mode, Integer topK) {
        return search(userId, knowledgeBaseId, query, mode, topK, false, null);
    }

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query, RetrievalMode mode,
                                          Integer topK, boolean rerank, Integer rerankCandidates) {
        return search(userId, knowledgeBaseId, query, mode, topK, rerank, rerankCandidates, null);
    }

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query, RetrievalMode mode,
                                          Integer topK, boolean rerank, Integer rerankCandidates, ContextMode contextMode) {
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
        int candidateLimit = rerank ? rerankCandidateLimit(limit, rerankCandidates) : limit;
        int bm25Limit = ragProperties.bm25CandidateSize();
        int vectorLimit = ragProperties.vectorCandidateSize();
        if (rerank) {
            bm25Limit = Math.max(bm25Limit, candidateLimit);
            vectorLimit = Math.max(vectorLimit, candidateLimit);
        }
        float[] queryVector = effectiveMode == RetrievalMode.BM25 ? null
                : embeddingService.embed(normalizedQuery, EmbeddingPurpose.QUERY);
        List<RetrievalHit> rankedChildren = retrievalService.retrieve(
                        effectiveMode,
                        userId.toString(),
                        knowledgeBaseId.toString(),
                        normalizedQuery,
                        queryVector,
                        bm25Limit, vectorLimit, candidateLimit, ragProperties.rrfK());
        if (!rerank) {
            return contextView(userId, knowledgeBaseId, normalizedQuery, effectiveMode, rankedChildren, null, contextMode);
        }
        var scores = reranker.score(normalizedQuery, rankedChildren.stream().map(RetrievalHit::content).toList());
        List<Integer> order = IntStream.range(0, rankedChildren.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer index) -> scores.get(index).logit()).reversed())
                .toList();
        var trace = new ArrayList<KnowledgeSearchResponse.RerankCandidate>(order.size());
        for (int i = 0; i < order.size(); i++) {
            int originalIndex = order.get(i);
            RetrievalHit hit = rankedChildren.get(originalIndex);
            var score = scores.get(originalIndex);
            trace.add(new KnowledgeSearchResponse.RerankCandidate(
                    hit.chunkId(), hit.provenance(), originalIndex + 1, hit.score(), i + 1, score.logit(),
                    score.inputTokens(), score.truncated()));
        }
        var topChildren = order.stream().limit(limit).map(rankedChildren::get).toList();
        return contextView(userId, knowledgeBaseId, normalizedQuery, effectiveMode, topChildren, List.copyOf(trace), contextMode);
    }

    private int rerankCandidateLimit(int topK, Integer requested) {
        int candidateLimit = requested == null ? rerankProperties.candidateSize() : requested;
        if (candidateLimit < topK || candidateLimit > RerankProperties.MAX_CANDIDATES) {
            throw new BusinessException("重排候选数量必须至少为 topK 且至多为 " + RerankProperties.MAX_CANDIDATES);
        }
        return candidateLimit;
    }

    public ContextComparison compareContexts(Long userId, Long knowledgeBaseId, String query, Integer topK) {
        return compareContexts(userId, knowledgeBaseId, query, topK, null, false, null);
    }

    public ContextComparison compareContexts(Long userId, Long knowledgeBaseId, String query, Integer topK,
                                             RetrievalMode mode, boolean rerank, Integer rerankCandidates) {
        var child = search(userId, knowledgeBaseId, query, mode == null ? RetrievalMode.RRF : mode,
                topK, rerank, rerankCandidates, ContextMode.CHILD);
        return new ContextComparison(child,
                expand(userId, knowledgeBaseId, child, ContextMode.PARENT),
                expand(userId, knowledgeBaseId, child, ContextMode.NEIGHBOR));
    }

    private KnowledgeSearchResponse contextView(Long userId, Long knowledgeBaseId, String normalizedQuery,
                                                RetrievalMode effectiveMode, List<RetrievalHit> rankedChildren,
                                                List<KnowledgeSearchResponse.RerankCandidate> rerankCandidates,
                                                ContextMode contextMode) {
        boolean legacyParent = contextMode == null && effectiveMode == RetrievalMode.PARENT;
        List<KnowledgeSearchResponse.Result> candidates = legacyParent
                ? parentAggregator.aggregate(userId.toString(), knowledgeBaseId.toString(), rankedChildren)
                : rankedChildren.stream().map(hit -> new KnowledgeSearchResponse.Result(
                        hit.chunkId(), hit.content(), hit.provenance(), hit.score())).toList();
        List<KnowledgeSearchResponse.Result> results = new ArrayList<>();
        List<KnowledgeSearchResponse.ContextMatch> matches = new ArrayList<>();
        var admitted = new HashSet<String>();
        int contextTokens = 0;
        for (var context : candidates) {
            int size = tokenCounter.count(context.content());
            // Preserve complete source chunks; later smaller contexts may still fit the same text budget.
            if (contextTokens + size > ragProperties.contextMaxTokens() || !admitted.add(context.chunkId())) { continue; }
            results.add(context);
            contextTokens += size;
            var evidence = rankedChildren.stream().filter(child -> context.chunkId().equals(child.chunkId())
                            || (legacyParent && context.chunkId().equals(child.parentChunkId())))
                    .map(child -> new KnowledgeSearchResponse.ChildEvidence(child.chunkId(), child.provenance(), child.score())).toList();
            matches.add(new KnowledgeSearchResponse.ContextMatch(context.chunkId(), evidence));
        }
        var base = new KnowledgeSearchResponse(
                normalizedQuery,
                results.isEmpty() ? KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE : null,
                List.copyOf(results), rankedChildren, List.copyOf(matches), contextTokens, effectiveMode, rerankCandidates,
                contextMode == null ? null : ContextMode.CHILD);
        return contextMode == null || contextMode == ContextMode.CHILD
                ? base : expand(userId, knowledgeBaseId, base, contextMode);
    }

    private KnowledgeSearchResponse expand(Long userId, Long knowledgeBaseId, KnowledgeSearchResponse base, ContextMode mode) {
        var admittedIds = new HashSet<>(base.hits().stream().map(KnowledgeSearchResponse.Result::chunkId).toList());
        var seeds = base.rankedChildren().stream().filter(child -> admittedIds.remove(child.chunkId())).toList();
        var expansion = contextExpansion.expand(userId.toString(), knowledgeBaseId.toString(), mode, seeds,
                ragProperties.contextMaxTokens() - base.contextTokens());
        var hits = new ArrayList<>(base.hits());
        hits.addAll(expansion.hits());
        var matches = new ArrayList<>(base.contextMatches());
        matches.addAll(expansion.matches());
        return new KnowledgeSearchResponse(base.query(), base.message(), List.copyOf(hits), base.rankedChildren(),
                List.copyOf(matches), base.contextTokens() + expansion.tokens(), base.mode(), base.rerankCandidates(), mode);
    }

    public record ContextComparison(KnowledgeSearchResponse childContext, KnowledgeSearchResponse parentContext,
                                    KnowledgeSearchResponse neighborContext) { }
}
