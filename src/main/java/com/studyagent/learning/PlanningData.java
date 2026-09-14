package com.studyagent.learning;

import java.util.List;

/** Teaching IDs are assigned by the server; retrieval chunk IDs remain source references. */
public final class PlanningData {
    private PlanningData() { }
    public record Source(Long documentId, String documentTitle, String documentHash,
                         String chunkId, String content, String location) { }
    public record Input(String version, Integer targetPointCount, List<Source> lessons, List<Source> exercises) { }
    public record Evidence(String sourceChunkId, String quote) { }
    public record Candidate(Long id, String topic, List<String> subtopics, List<String> sourceChunkIds, List<Evidence> evidence) { }
    public record Uncovered(String sourceChunkId, String reason) { }
    public record Extraction(List<Candidate> points, List<Uncovered> uncovered) { }
    public record Point(Long id, List<String> path, String topic, List<String> subtopics, List<String> sourceChunkIds, List<Evidence> evidence) { }
    public record Chapter(Long id, String title, List<Point> points) { }
    public record Outline(List<Chapter> chapters) { }
    public record Importance(Long knowledgePointId, String sourceChunkId, String quote,
                             String lessonSourceChunkId, String lessonQuote, String reason, String priority) { }
    public record Unmatched(String sourceChunkId, String quote, String reason) { }
    public record Emphasis(List<Importance> matches, List<Unmatched> unmatched) { }
    public record Task(Long knowledgePointId, Long chapterId, String chapterTitle, String topic,
                       List<String> subtopics, List<String> sourceChunkIds, String priority,
                       int estimatedMinutes, String reason) { }
    public record TaskBatch(List<Task> tasks) { }
    public record OutlineNode(Long id, String title, String priority, List<OutlineNode> children) { }
    public record Result(Outline outline, Emphasis emphasis, List<Task> tasks, List<OutlineNode> nodes) { }
}
