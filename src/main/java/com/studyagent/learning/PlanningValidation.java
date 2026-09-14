package com.studyagent.learning;

import static com.studyagent.learning.PlanningData.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyagent.common.exception.BusinessException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validate generated relationships before assigning durable teaching IDs or publishing a plan. */
public final class PlanningValidation {
    private PlanningValidation() { }

    public static Extraction extraction(JsonNode root, List<Source> sources) {
        Set<String> allowed = sources.stream().map(Source::chunkId).collect(Collectors.toSet());
        Map<String, Source> sourceMap = sources.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Set<String> covered = new HashSet<>();
        List<Candidate> points = new ArrayList<>();
        for (JsonNode node : array(root, "points")) {
            List<String> refs = strings(node, "sourceChunkIds");
            require(!refs.isEmpty() && allowed.containsAll(refs), "大纲提取引用了本批次以外的来源");
            List<Evidence> evidence = new ArrayList<>();
            for (JsonNode item : array(node, "evidence")) {
                String source = text(item, "sourceChunkId");
                require(refs.contains(source), "知识点依据必须使用引用来源");
                List<PlanningEvidence.Excerpt> excerpts = PlanningEvidence.excerpts(sourceMap.get(source).content());
                JsonNode excerpt = item.get("excerptNo");
                require(excerpt != null && excerpt.isIntegralNumber() && excerpt.canConvertToInt()
                        && excerpt.intValue() >= 1 && excerpt.intValue() <= excerpts.size(), "知识点依据必须选择该来源已提供的摘录编号");
                String quote = excerpts.get(excerpt.intValue() - 1).text();
                quotedSource(sourceMap, source, quote);
                evidence.add(new Evidence(source, quote));
            }
            Set<String> evidenced = evidence.stream().map(Evidence::sourceChunkId).collect(Collectors.toSet());
            require(evidenced.equals(new HashSet<>(refs)), "知识点每个引用来源都需要原文依据；topic=" + text(node, "topic")
                    + "；缺少依据的sourceChunkId=" + refs.stream().filter(ref -> !evidenced.contains(ref)).toList());
            covered.addAll(refs);
            points.add(new Candidate(IdWorker.getId(), text(node, "topic"), strings(node, "subtopics"), refs, List.copyOf(evidence)));
        }
        List<Uncovered> uncovered = new ArrayList<>();
        Set<String> noted = new HashSet<>();
        // No omitted sources is a valid result; the coverage check below still rejects actual omissions.
        Iterable<JsonNode> uncoveredNodes = root.has("uncovered") ? array(root, "uncovered") : List.of();
        for (JsonNode node : uncoveredNodes) {
            String id = text(node, "sourceChunkId");
            // One retrieval parent may contain both useful prose and unparsed image placeholders.
            require(allowed.contains(id) && noted.add(id), "未提取说明必须属于本批次且不得重复");
            covered.add(id);
            uncovered.add(new Uncovered(id, text(node, "reason")));
        }
        require(covered.equals(allowed), "大纲提取遗漏输入来源，必须列出知识点或未提取原因");
        return new Extraction(List.copyOf(points), List.copyOf(uncovered));
    }

    public static Outline outline(JsonNode root, List<Candidate> candidates, Integer targetPointCount) {
        Map<Long, Candidate> allowed = candidates.stream().collect(Collectors.toMap(Candidate::id, c -> c));
        Set<Long> consumed = new HashSet<>();
        JsonNode generated = array(root, "points");
        require(targetPointCount == null || generated.size() == targetPointCount,
                "大纲知识点数量与用户指定数量不一致");
        Map<String, List<Point>> byChapter = new java.util.LinkedHashMap<>();
        for (JsonNode point : generated) {
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            LinkedHashSet<Evidence> evidence = new LinkedHashSet<>();
            for (JsonNode value : array(point, "candidateIds")) {
                require(value.isTextual() && value.asText().matches("C[1-9][0-9]*"), "候选引用必须使用本次C编号");
                Long number = id(new com.fasterxml.jackson.databind.node.TextNode(value.asText().substring(1)));
                require(number <= candidates.size(), "大纲合并引用未知候选编号：" + value.asText());
                Long id = candidates.get(number.intValue() - 1).id();
                require(allowed.containsKey(id), "大纲引用未知候选知识点：" + id);
                consumed.add(id);
                refs.addAll(allowed.get(id).sourceChunkIds());
                evidence.addAll(allowed.get(id).evidence());
            }
            require(!refs.isEmpty(), "合并知识点必须有候选来源");
            List<String> path = strings(point, "path");
            require(!path.isEmpty(), "学习节点需要所属目录路径");
            byChapter.computeIfAbsent(path.getFirst(), key -> new ArrayList<>()).add(
                    new Point(IdWorker.getId(), path, text(point, "topic"), List.of(), List.copyOf(refs), List.copyOf(evidence)));
        }
        require(!byChapter.isEmpty() && consumed.equals(allowed.keySet()), "合并大纲必须保留全部候选知识点，可合并同义项；遗漏候选ID："
                + java.util.stream.IntStream.range(0, candidates.size()).filter(i -> !consumed.contains(candidates.get(i).id()))
                        .mapToObj(i -> "C" + (i + 1)).toList());
        return new Outline(byChapter.entrySet().stream()
                .map(entry -> new Chapter(IdWorker.getId(), entry.getKey(), List.copyOf(entry.getValue()))).toList());
    }

    public static Outline selectEmphasisOutline(JsonNode root, Outline outline) {
        Set<Long> allowed = outline.chapters().stream().flatMap(c -> c.points().stream()).map(Point::id).collect(Collectors.toSet());
        Set<Long> selected = new HashSet<>();
        for (JsonNode node : array(root, "knowledgePointIds")) {
            Long pointId = id(node);
            require(allowed.contains(pointId), "习题定位引用未知知识点");
            require(selected.add(pointId), "习题定位重复知识点");
        }
        return new Outline(outline.chapters().stream().map(c -> new Chapter(c.id(), c.title(),
                c.points().stream().filter(p -> selected.contains(p.id())).toList()))
                .filter(c -> !c.points().isEmpty()).toList());
    }

    public static Emphasis emphasisByReference(JsonNode root, Outline outline, List<Source> exercises, List<Source> lessons) {
        JsonNode resolved = root.deepCopy();
        Map<String, Source> sources = exercises.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Map<Long, Point> points = outline.chapters().stream().flatMap(c -> c.points().stream()).collect(Collectors.toMap(Point::id, p -> p));
        for (String field : List.of("matches", "unmatched")) {
            for (JsonNode value : array(resolved, field)) {
                require(value.isObject(), "习题映射必须为对象");
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) value;
                String sourceId = text(node, "sourceChunkId");
                require(sources.containsKey(sourceId), "习题摘录引用未知来源");
                var excerpts = PlanningEvidence.excerpts(sources.get(sourceId).content());
                int excerpt = referenceIndex(node.get("excerptNo"), excerpts.size());
                node.put("quote", excerpts.get(excerpt).text());
                if ("matches".equals(field)) {
                    Point point = points.get(id(node.get("knowledgePointId")));
                    require(point != null, "习题映射引用未知知识点");
                    Evidence evidence = point.evidence().get(referenceIndex(node.get("lessonEvidenceNo"), point.evidence().size()));
                    node.put("lessonSourceChunkId", evidence.sourceChunkId());
                    node.put("lessonQuote", evidence.quote());
                }
            }
        }
        return emphasis(resolved, outline, exercises, lessons);
    }

    private static int referenceIndex(JsonNode value, int size) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 1 && value.intValue() <= size, "摘录编号超出已提供范围");
        return value.intValue() - 1;
    }

    public static Emphasis emphasis(JsonNode root, Outline outline, List<Source> exercises, List<Source> lessons) {
        Set<Long> pointIds = outline.chapters().stream().flatMap(c -> c.points().stream()).map(Point::id).collect(Collectors.toSet());
        Map<String, Source> sources = exercises.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Map<String, Source> lessonSources = lessons.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Map<Long, Point> points = outline.chapters().stream().flatMap(c -> c.points().stream()).collect(Collectors.toMap(Point::id, p -> p));
        Set<String> covered = new HashSet<>();
        List<Importance> matches = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (JsonNode node : array(root, "matches")) {
            Long point = id(node.get("knowledgePointId"));
            require(pointIds.contains(point), "习题重点只能映射到既有知识点");
            String source = text(node, "sourceChunkId");
            String quote = text(node, "quote");
            quotedSource(sources, source, quote);
            String lessonSource = text(node, "lessonSourceChunkId");
            String lessonQuote = text(node, "lessonQuote");
            require(points.get(point).sourceChunkIds().contains(lessonSource), "课件摘录必须属于被映射知识点的来源");
            quotedSource(lessonSources, lessonSource, lessonQuote);
            require(points.get(point).evidence().stream().anyMatch(e -> e.sourceChunkId().equals(lessonSource)
                    && normalize(e.quote()).contains(normalize(lessonQuote))), "重点依据必须来自该知识点已确认的摘录");
            String priority = text(node, "priority");
            require(Set.of("HIGH", "MEDIUM").contains(priority), "习题重点必须为 HIGH 或 MEDIUM");
            require(duplicates.add(point + "/" + source + "/" + normalize(quote)), "重复习题映射");
            matches.add(new Importance(point, source, quote, lessonSource, lessonQuote, text(node, "reason"), priority));
            covered.add(source);
        }
        List<Unmatched> unmatched = new ArrayList<>();
        for (JsonNode node : array(root, "unmatched")) {
            String source = text(node, "sourceChunkId");
            String quote = text(node, "quote");
            quotedSource(sources, source, quote);
            unmatched.add(new Unmatched(source, quote, text(node, "reason")));
            covered.add(source);
        }
        require(covered.equals(sources.keySet()), "习题批次有未处理的来源，需要保留未匹配说明");
        return new Emphasis(List.copyOf(matches), List.copyOf(unmatched));
    }

    public static Emphasis reviewEmphasis(JsonNode root, Emphasis proposed) {
        Map<Integer, Importance> accepted = new HashMap<>();
        Map<Integer, Unmatched> rejected = new HashMap<>();
        Set<Integer> reviewed = new HashSet<>();
        for (JsonNode node : array(root, "decisions")) {
            JsonNode index = node.get("matchIndex");
            require(index != null && index.isIntegralNumber() && index.canConvertToInt()
                    && index.intValue() >= 0 && index.intValue() < proposed.matches().size(), "重点复核引用未知匹配序号");
            int i = index.intValue();
            require(reviewed.add(i), "重点复核不得重复同一匹配");
            JsonNode supported = node.get("supported");
            require(supported != null && supported.isBoolean(), "重点复核supported必须为布尔值");
            text(node, "testedClaim");
            text(node, "lessonClaim");
            String reason = text(node, "reason");
            Importance m = proposed.matches().get(i);
            if (supported.booleanValue()) {
                accepted.put(i, new Importance(m.knowledgePointId(), m.sourceChunkId(), m.quote(),
                        m.lessonSourceChunkId(), m.lessonQuote(), reason, m.priority()));
            } else {
                rejected.put(i, new Unmatched(m.sourceChunkId(), m.quote(), "课件依据复核未通过：" + reason));
            }
        }
        require(reviewed.size() == proposed.matches().size(), "重点复核必须覆盖全部候选匹配");
        List<Importance> matches = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>(proposed.unmatched());
        for (int i = 0; i < proposed.matches().size(); i++) {
            if (accepted.containsKey(i)) { matches.add(accepted.get(i)); }
            else { unmatched.add(rejected.get(i)); }
        }
        return new Emphasis(List.copyOf(matches), List.copyOf(unmatched));
    }

    public static List<Task> tasks(JsonNode root, Outline outline, Emphasis emphasis) {
        Map<Long, Point> points = new HashMap<>();
        Map<Long, Chapter> chapters = new HashMap<>();
        outline.chapters().forEach(c -> c.points().forEach(p -> { points.put(p.id(), p); chapters.put(p.id(), c); }));
        Map<Long, String> priorities = new HashMap<>();
        emphasis.matches().forEach(m -> priorities.merge(m.knowledgePointId(), m.priority(),
                (a, b) -> "HIGH".equals(a) || "HIGH".equals(b) ? "HIGH" : "MEDIUM"));
        List<Task> tasks = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (JsonNode node : array(root, "tasks")) {
            Long id = id(node.get("knowledgePointId"));
            require(points.containsKey(id) && used.add(id), "任务必须使用既有且不重复的知识点 ID");
            JsonNode minutes = node.get("baseMinutes");
            require(minutes != null && minutes.isIntegralNumber() && minutes.canConvertToInt()
                    && minutes.intValue() > 0 && minutes.intValue() <= 180, "baseMinutes 必须为 1–180 的整数");
            String priority = priorities.getOrDefault(id, "NORMAL");
            int allocated = minutes.intValue() + ("HIGH".equals(priority) ? 10 : "MEDIUM".equals(priority) ? 5 : 0);
            Point p = points.get(id);
            Chapter c = chapters.get(id);
            String basis = emphasis.matches().stream().filter(m -> m.knowledgePointId().equals(id))
                    .map(Importance::reason).distinct().collect(Collectors.joining("；"));
            String reason = text(node, "reason") + (basis.isEmpty() ? " 本批习题暂无直接匹配依据，按基础内容安排。"
                    : " 习题依据：" + basis + "；追加练习" + (allocated - minutes.intValue()) + "分钟。");
            tasks.add(new Task(id, c.id(), String.join(" / ", p.path()), p.topic(), p.subtopics(), p.sourceChunkIds(), priority,
                    allocated, reason));
        }
        require(used.equals(points.keySet()), "计划不能删除未被习题覆盖的基础知识点");
        return List.copyOf(tasks);
    }

    private static void quotedSource(Map<String, Source> sources, String id, String quote) {
        require(sources.containsKey(id), "引用来源不属于当前批次: " + id);
        require(normalize(sources.get(id).content()).contains(normalize(quote)),
                "引用必须是当前批次资料中的连续原文摘录，sourceChunkId=" + id + "，不匹配摘录="
                        + quote.substring(0, Math.min(quote.length(), 120)));
    }
    private static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", ""); }
    private static Long id(JsonNode node) {
        try {
            require(node != null && (node.isTextual() || node.isIntegralNumber()), "ID 必须是整数或整数字符串");
            Long id = Long.valueOf(node.asText());
            require(id > 0, "ID 必须为正整数");
            return id;
        } catch (NumberFormatException e) { throw new BusinessException("非法教学 ID"); }
    }
    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isArray(), field + " 必须是数组");
        return value;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.asText().isBlank(), field + " 必须是非空文本");
        return value.asText().trim();
    }
    private static List<String> strings(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            require(value.isTextual() && !value.asText().isBlank(), field + " 必须仅含非空文本");
            require(!result.contains(value.asText().trim()), field + " 不得重复");
            result.add(value.asText().trim());
        }
        return List.copyOf(result);
    }
    private static void require(boolean accepted, String message) { if (!accepted) { throw new BusinessException(message); } }
}
