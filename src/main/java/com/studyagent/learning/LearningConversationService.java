package com.studyagent.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.model.LearningTurn;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningConversationService {
    private final LearningTurnPersistence turns;
    private final LearningPersistenceService learning;
    private final LearningConversationGateway gateway;
    private final LearningConversationCompactor compactor;
    private final LearningTraceService traces;
    private final ObjectMapper mapper;

    public LearningTurn message(Long userId, Long sessionId, String requestId, String message,
                                Consumer<LearningConversationGateway.Progress> progress) {
        var claim = turns.claim(userId, sessionId, requestId, message, traces.start());
        if (!claim.execute()) { return claim.turn(); }
        LearningTurn turn = claim.turn();
        try {
            traces.record(userId, turn.getTraceId(), sessionId, "TURN", "STARTED", "处理用户消息，尝试 " + turn.getAttemptCount(), "STARTED");
            var session = learning.requireSession(userId, sessionId);
            if ("MODEL".equals(turn.getPhase())) {
                var point = learning.requireActivePoint(session);
                List<QuizQuestionDraft> quiz = List.of();
                if (KnowledgePointStatus.QUIZZING.name().equals(point.getStatus()) || KnowledgePointStatus.CARD_GENERATING.name().equals(point.getStatus()) || KnowledgePointStatus.FEEDBACK.name().equals(point.getStatus())) {
                    quiz = questions(learning.requireQuiz(point).getQuestionsJson());
                }
                var result = gateway.respond(session, point, turn, turns.context(userId, sessionId), quiz, progress);
                turn = turns.commitModel(turn, result);
                traces.record(userId, turn.getTraceId(), sessionId, "TURN", "ARTIFACTS_COMMITTED",
                        result.intent().action() == null ? "答疑已保存，知识点状态未推进" : "已提交 " + result.intent().action(), "SUCCEEDED");
            }
            if (!"ARTIFACTS_COMMITTED".equals(turn.getPhase())) { throw new BusinessException("无法恢复未知回合阶段：" + turn.getPhase()); }
            progress.accept(new LearningConversationGateway.Progress("progress", "正在保存学习上下文"));
            String context = compactor.compact(session, turn, turns.context(userId, sessionId));
            LearningTurn completed = turns.complete(turn, context);
            traces.record(userId, turn.getTraceId(), sessionId, "TURN", "COMPLETED", "回合与上下文均已持久化", "SUCCEEDED");
            return completed;
        } catch (RuntimeException error) {
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            turns.fail(turn, reason);
            traces.record(userId, turn.getTraceId(), sessionId, "TURN", "FAILED", reason, "FAILED");
            return turns.require(userId, sessionId, turn.getId());
        }
    }

    // Structured API submissions share the same turn, tool and persistence path as the chat UI.
    public LearningTurn submitQuiz(Long userId, Long sessionId, List<String> answers) {
        var point = learning.requireActivePoint(learning.requireSession(userId, sessionId));
        var quiz = learning.requireQuiz(point);
        var questions = questions(quiz.getQuestionsJson());
        if (answers == null || answers.size() != questions.size()) {
            throw new BusinessException("请完整提交当前测验的全部答案");
        }
        StringBuilder message = new StringBuilder("提交答案：");
        for (int i = 0; i < questions.size(); i++) {
            int index = questions.get(i).options().indexOf(answers.get(i));
            if (index < 0) { throw new BusinessException("答案必须是对应题目的选项"); }
            message.append(i + 1).append('.').append((char) ('A' + index)).append(' ');
        }
        var turn = message(userId, sessionId, java.util.UUID.randomUUID().toString(), message.toString(), event -> { });
        if (!"SUCCEEDED".equals(turn.getStatus())) {
            throw new BusinessException("回合 " + turn.getId() + " 未完成：" + turn.getErrorMessage());
        }
        try {
            if (!"GRADE".equals(mapper.readTree(turn.getArtifactJson()).path("type").asText())) {
                throw new BusinessException("模型本轮选择了答疑，请通过消息入口继续：" + turn.getAssistantMessage());
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException("回合产物无法读取");
        }
        return turn;
    }

    private List<QuizQuestionDraft> questions(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException e) { throw new BusinessException("已保存的测验无法读取，不能评分或继续学习"); }
    }
}
