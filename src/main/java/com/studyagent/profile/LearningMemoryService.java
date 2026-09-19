package com.studyagent.profile;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.algo.chunk.TokenCounter;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.LearningMemoryProperties;
import com.studyagent.learning.QuizFeedback;
import com.studyagent.learning.QuizQuestionDraft;
import com.studyagent.mapper.LearningMemoryMapper;
import com.studyagent.model.*;
import com.studyagent.rag.web.KnowledgeBaseService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningMemoryService {
    private final LearningMemoryMapper memories;
    private final KnowledgeBaseService knowledgeBases;
    private final ObjectMapper json;
    private final TokenCounter tokens;
    private final LearningMemoryProperties properties;

    public LearningPreference preference(Long userId) {
        var saved = memories.preference(userId);
        if (saved != null) return saved;
        var empty = new LearningPreference();
        empty.setUserId(userId); empty.setExplanationStyle(""); empty.setLearningGoal(""); empty.setMemoryEnabled(true);
        return empty;
    }

    public LearningPreference savePreference(Long userId, String style, String goal, boolean enabled) {
        var value = new LearningPreference();
        value.setUserId(userId); value.setExplanationStyle(style.trim()); value.setLearningGoal(goal.trim());
        value.setMemoryEnabled(enabled); value.setUpdatedAt(LocalDateTime.now());
        memories.savePreference(value);
        return value;
    }

    // Called in the grading transaction: a failed grade must not leave a memory of an uncommitted result.
    public void recordQuiz(LearningSession session, KnowledgePoint point, Quiz quiz) {
        if (!preference(session.getUserId()).isMemoryEnabled()) return;
        var questions = read(quiz.getQuestionsJson(), new TypeReference<List<QuizQuestionDraft>>() {});
        var feedback = read(quiz.getFeedbackJson(), new TypeReference<List<QuizFeedback>>() {});
        var mistakes = new ArrayList<Mistake>();
        for (var item : feedback) {
            if (!item.correct()) {
                var question = questions.get(item.questionIndex());
                // Keep observations compact; full answers and feedback remain in the originating chat.
                mistakes.add(new Mistake(shortText(question.question(), 240), question.sourceChunkId()));
            }
        }
        var memory = new LearningMemory();
        memory.setId(IdWorker.getId()); memory.setUserId(session.getUserId());
        memory.setKnowledgeBaseId(session.getKnowledgeBaseId()); memory.setSessionId(session.getId());
        memory.setQuizId(quiz.getId()); memory.setTopic(shortText(point.getTopic(), 512));
        memory.setCorrectCount((int) feedback.stream().filter(QuizFeedback::correct).count());
        memory.setQuestionCount(feedback.size()); memory.setMistakesJson(write(mistakes));
        memory.setRecordedAt(quiz.getAnsweredAt());
        memories.record(memory);
    }

    public List<MemoryView> list(Long userId, Long knowledgeBaseId) {
        knowledgeBases.requireOwned(userId, knowledgeBaseId);
        return memories.recent(userId, knowledgeBaseId, 50).stream().map(this::view).toList();
    }

    public void setIncluded(Long userId, Long knowledgeBaseId, Long id, boolean included) {
        knowledgeBases.requireOwned(userId, knowledgeBaseId);
        if (memories.setIncluded(userId, knowledgeBaseId, id, included) == 0) {
            throw new BusinessException(404, "学习记忆不存在");
        }
    }

    public String context(LearningSession session, KnowledgePoint point) {
        var preference = preference(session.getUserId());
        if (!preference.isMemoryEnabled()) return "用户已关闭跨会话学习记忆。";
        var recalled = memories.recall(session.getUserId(), session.getKnowledgeBaseId(), session.getId(),
                shortText(point.getTopic(), 512), properties.recallLimit());
        var observations = new ArrayList<MemoryView>();
        String result = contextJson(preference, observations);
        if (tokens.count(result) > properties.contextTokens()) return "学习偏好超出记忆预算，本轮未注入。";
        for (var memory : recalled) {
            observations.add(view(memory));
            String candidate = contextJson(preference, observations);
            if (tokens.count(candidate) > properties.contextTokens()) { observations.removeLast(); continue; }
            result = candidate;
        }
        return result;
    }

    private String contextJson(LearningPreference preference, List<MemoryView> observations) {
        return write(Map.of("用户自述偏好", preference.getExplanationStyle(), "用户自述目标", preference.getLearningGoal(),
                "此前其他聊天的测验记录", observations));
    }

    private MemoryView view(LearningMemory memory) {
        return new MemoryView(memory.getId(), memory.getSessionId(), memory.getTopic(), memory.getCorrectCount(),
                memory.getQuestionCount(), read(memory.getMistakesJson(), new TypeReference<List<Mistake>>() {}),
                memory.isIncluded(), memory.getRecordedAt());
    }

    private String shortText(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("学习记忆序列化失败", e); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("学习记忆来源读取失败", e); }
    }

    public record Mistake(String question, String sourceChunkId) {}
    public record MemoryView(Long id, Long sessionId, String topic, int correctCount, int questionCount,
                             List<Mistake> mistakes, boolean included, LocalDateTime recordedAt) {}
}
