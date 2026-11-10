package com.studyagent.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.algo.chunk.TokenCounter;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContextExpansion {
    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;
    private final ObjectMapper json;
    private final TokenCounter tokenCounter;

    Expansion expand(String userId, String knowledgeBaseId, ContextMode mode, List<RetrievalHit> seeds, int budget) {
        if (mode == ContextMode.CHILD || budget <= 0 || seeds.isEmpty()) {
            return new Expansion(List.of(), List.of(), 0);
        }
        Map<Slot, List<RetrievalHit>> requests = new LinkedHashMap<>();
        Map<Generation, List<Span>> covered = new LinkedHashMap<>();
        for (RetrievalHit seed : seeds) {
            Generation generation = generation(seed);
            covered.computeIfAbsent(generation, ignored -> new ArrayList<>())
                    .add(span(seed.provenance().sourceLocation(), seed.content()));
            if (mode == ContextMode.PARENT) {
                if (seed.parentChunkId() != null && !seed.parentChunkId().isBlank()) {
                    register(requests, new Slot(generation, seed.parentChunkId(), null), seed);
                }
            } else {
                int ordinal = seed.position().ordinal();
                if (ordinal > 0) {
                    register(requests, new Slot(generation, null, ordinal - 1), seed);
                }
                register(requests, new Slot(generation, null, ordinal + 1), seed);
            }
        }
        Map<Slot, ElasticsearchChunkDocument> candidates = load(userId, knowledgeBaseId, requests);
        var results = new ArrayList<KnowledgeSearchResponse.Result>();
        var matches = new ArrayList<KnowledgeSearchResponse.ContextMatch>();
        int used = 0;
        for (var entry : requests.entrySet()) {
            Slot slot = entry.getKey();
            var candidate = candidates.get(slot);
            if (candidate == null || seeds.stream().anyMatch(seed -> seed.chunkId().equals(candidate.chunkId()))) {
                continue;
            }
            var location = span(candidate.sourceLocation(), candidate.content());
            List<Span> usedSpans = covered.get(slot.generation());
            String content = uncovered(candidate.content(), location, usedSpans);
            if (content.isBlank()) {
                continue;
            }
            int tokens = tokenCounter.count(content);
            if (tokens > budget - used) {
                continue;
            }
            var source = new RetrievalHit.Provenance(
                    candidate.documentId(), candidate.documentTitle(), candidate.sourceLocation());
            results.add(new KnowledgeSearchResponse.Result(
                    candidate.chunkId(), content, source, entry.getValue().getFirst().score()));
            matches.add(new KnowledgeSearchResponse.ContextMatch(candidate.chunkId(), entry.getValue().stream()
                    .map(seed -> new KnowledgeSearchResponse.ChildEvidence(seed.chunkId(), seed.provenance(), seed.score()))
                    .toList()));
            usedSpans.add(location);
            used += tokens;
        }
        return new Expansion(results, matches, used);
    }

    private Map<Slot, ElasticsearchChunkDocument> load(String userId, String knowledgeBaseId,
                                                       Map<Slot, List<RetrievalHit>> requests) {
        if (requests.isEmpty()) {
            return Map.of();
        }
        List<Query> slots = requests.keySet().stream().map(this::query).toList();
        Map<Slot, ElasticsearchChunkDocument> result = new LinkedHashMap<>();
        try {
            var response = client.search(search -> search
                    .index(properties.readAlias()).size(slots.size())
                    .source(source -> source.filter(filter -> filter.excludes("embedding")))
                    .query(query -> query.bool(bool -> bool
                            .filter(term("user_id", userId), term("knowledge_base_id", knowledgeBaseId))
                            .should(slots).minimumShouldMatch("1"))), ElasticsearchChunkDocument.class);
            for (var hit : response.hits().hits()) {
                var chunk = Objects.requireNonNull(hit.source(), "Context expansion requires Elasticsearch _source");
                var generation = new Generation(hit.index(), chunk.documentId(), chunk.chunkerVersion(), chunk.createdAt());
                Slot slot = "PARENT".equals(chunk.chunkType())
                        ? new Slot(generation, chunk.chunkId(), null) : new Slot(generation, null, chunk.chunkIndex());
                // The ES date field matches at millisecond precision. Compare the full timestamp in _source.
                if (requests.containsKey(slot)) {
                    result.putIfAbsent(slot, chunk);
                }
            }
        } catch (IOException ex) {
            throw new BusinessException(503, "读取上下文扩展块失败: " + ex.getMessage());
        }
        return result;
    }

    private Query query(Slot slot) {
        var generation = slot.generation();
        Query selector = slot.parentId() == null
                ? Query.of(q -> q.term(t -> t.field("chunk_index").value(FieldValue.of(slot.ordinal().longValue()))))
                : term("chunk_id", slot.parentId());
        return Query.of(q -> q.bool(bool -> bool.filter(
                term("_index", generation.index()),
                term("document_id", generation.documentId()),
                term("chunker_version", generation.chunkerVersion()),
                term("created_at", generation.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)),
                term("chunk_type", slot.parentId() == null ? "CHILD" : "PARENT"),
                selector)));
    }

    private Query term(String field, String value) {
        return Query.of(query -> query.term(term -> term.field(field).value(value)));
    }

    private void register(Map<Slot, List<RetrievalHit>> requests, Slot slot, RetrievalHit seed) {
        requests.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(seed);
    }

    private Generation generation(RetrievalHit seed) {
        var position = seed.position();
        if (position == null) {
            throw new BusinessException(409, "命中子块缺少索引批次或位置，无法安全扩展: " + seed.chunkId());
        }
        return new Generation(position.index(), seed.provenance().documentId(), position.chunkerVersion(), position.createdAt());
    }

    private Span span(String sourceLocation, String text) {
        if (sourceLocation != null) {
            try {
                var coordinates = json.readTree(sourceLocation);
                int start = coordinates.path("startOffset").asInt(-1);
                int end = coordinates.path("endOffset").asInt(-1);
                if (start >= 0 && end - start == text.length()) {
                    return new Span(start, end);
                }
            } catch (JsonProcessingException ex) {
                throw new BusinessException(409, "上下文扩展块的来源坐标格式无效");
            }
        }
        throw new BusinessException(409, "上下文扩展块的来源坐标与正文不匹配");
    }

    private String uncovered(String text, Span location, List<Span> covered) {
        var parts = new ArrayList<String>();
        int cursor = location.start();
        for (Span used : covered.stream().sorted(Comparator.comparingInt(Span::start)).toList()) {
            if (used.end() <= cursor || used.start() >= location.end()) {
                continue;
            }
            if (used.start() > cursor) {
                parts.add(text.substring(cursor - location.start(), used.start() - location.start()));
            }
            cursor = Math.max(cursor, used.end());
        }
        if (cursor < location.end()) {
            parts.add(text.substring(cursor - location.start()));
        }
        return String.join("\n\n", parts);
    }

    record Expansion(List<KnowledgeSearchResponse.Result> hits, List<KnowledgeSearchResponse.ContextMatch> matches, int tokens) { }
    private record Generation(String index, String documentId, String chunkerVersion, LocalDateTime createdAt) { }
    private record Slot(Generation generation, String parentId, Integer ordinal) { }
    private record Span(int start, int end) { }
}
