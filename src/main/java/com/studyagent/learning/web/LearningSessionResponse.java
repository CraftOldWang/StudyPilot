package com.studyagent.learning.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

public record LearningSessionResponse(
        Long id,
        String learningGoal,
        Long knowledgeBaseId,
        String status,
        @JsonInclude(JsonInclude.Include.ALWAYS) String errorMessage,
        @JsonInclude(JsonInclude.Include.ALWAYS) KnowledgePointResponse activeKnowledgePoint,
        List<KnowledgePointResponse> plan,
        @JsonInclude(JsonInclude.Include.ALWAYS) QuizResponse currentQuiz,
        List<CardResponse> cards) {

    public record KnowledgePointResponse(
            Long id,
            Integer sequenceNo,
            String topic,
            List<String> subtopics,
            Integer estimatedMinutes,
            String status,
            @JsonInclude(JsonInclude.Include.ALWAYS) String explanation,
            @JsonInclude(JsonInclude.Include.ALWAYS) String errorMessage,
            Long chapterId,
            String chapterTitle,
            String priority,
            List<String> sourceChunkIds,
            Long outlineNodeId) {
        public KnowledgePointResponse(Long id, Integer sequenceNo, String topic, List<String> subtopics,
                Integer estimatedMinutes, String status, String explanation, String errorMessage) {
            this(id, sequenceNo, topic, subtopics, estimatedMinutes, status, explanation, errorMessage,
                    null, null, null, List.of(), null);
        }
    }

    public record QuizResponse(
            Long quizId,
            Long knowledgePointId,
            List<QuestionResponse> questions,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer score,
            @JsonInclude(JsonInclude.Include.ALWAYS) List<FeedbackResponse> feedback) {
    }

    public record QuestionResponse(
            int questionIndex,
            String question,
            List<String> options,
            String sourceChunkId) {
    }

    public record FeedbackResponse(
            int questionIndex,
            boolean correct,
            String correctAnswer,
            String explanation) {
    }

    public record CardResponse(
            Long id,
            String front,
            String back,
            @JsonInclude(JsonInclude.Include.ALWAYS) String sourceChunkId) {
    }

    public record TraceEventResponse(
            Integer sequenceNo,
            String stage,
            String eventType,
            String summary,
            String status,
            LocalDateTime createdAt) {
    }
}
