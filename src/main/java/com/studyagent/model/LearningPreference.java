package com.studyagent.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class LearningPreference {
    private Long userId;
    private String explanationStyle;
    private String learningGoal;
    private boolean memoryEnabled;
    private LocalDateTime updatedAt;
}
