package com.studyagent.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class LearningMemory {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long sessionId;
    private Long quizId;
    private String topic;
    private int correctCount;
    private int questionCount;
    private String mistakesJson;
    private boolean included;
    private LocalDateTime recordedAt;
}
