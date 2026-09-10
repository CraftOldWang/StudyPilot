package com.studyagent.learning;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.LearningConversationProperties;
import com.studyagent.mapper.LearningContextMapper;
import com.studyagent.mapper.LearningSessionMapper;
import com.studyagent.mapper.LearningTurnMapper;
import com.studyagent.model.*;
import com.studyagent.review.ReviewCardService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningTurnPersistence {
    private final LearningSessionMapper sessions;
    private final LearningTurnMapper turns;
    private final LearningContextMapper contexts;
    private final LearningPersistenceService learning;
    private final ReviewCardService cards;
    private final LearningConversationProperties properties;
    private final ObjectMapper mapper;
    private final LearningContextInitializer initializer;

    @Transactional
    public Claim claim(Long userId, Long sessionId, String requestId, String message, String traceId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || message == null || message.isBlank()) {
            throw new BusinessException("requestId 和消息不能为空，requestId 不超过64字符");
        }
        LearningSession session = lockSession(userId, sessionId);
        String hash = hash(message.trim());
        LearningTurn turn = turns.selectOne(Wrappers.<LearningTurn>query().eq("session_id", sessionId).eq("request_id", requestId));
        if (turn != null) {
            if (!hash.equals(turn.getInputHash())) { throw new BusinessException(409, "同一 requestId 不能提交不同消息"); }
            if ("SUCCEEDED".equals(turn.getStatus())) { return new Claim(turn, false); }
        }
        if (session.getActiveTurnId() != null && (turn == null || !session.getActiveTurnId().equals(turn.getId()))) {
            throw new BusinessException(409, "当前会话有未完成回合，请先查询或恢复回合 " + session.getActiveTurnId());
        }
        if (turn != null && "RUNNING".equals(turn.getStatus()) && turn.getLeaseUntil().isAfter(LocalDateTime.now())) {
            return new Claim(turn, false);
        }
        KnowledgePoint point = learning.requireActivePoint(session);
        if (KnowledgePointStatus.CARD_CONFIRMING.name().equals(point.getStatus())) {
            throw new BusinessException("卡片已确认，正在写入Anki或整理摘要，请完成或重试确认操作");
        }
        if (turn != null && !point.getId().equals(turn.getKnowledgePointId())) {
            throw new BusinessException(409, "原回合所属知识点已结束，不能在新知识点重放");
        }
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        if (turn == null) {
            turn = new LearningTurn();
            turn.setUserId(userId); turn.setSessionId(sessionId); turn.setKnowledgePointId(point.getId());
            turn.setRequestId(requestId); turn.setInputHash(hash); turn.setUserMessage(message.trim());
            turn.setStatus("RUNNING"); turn.setPhase("MODEL"); turn.setTraceId(traceId); turn.setAttemptCount(1);
            turn.setProcessingToken(token); turn.setLeaseUntil(now.plusSeconds(properties.leaseSeconds()));
            turn.setCreatedAt(now); turn.setUpdatedAt(now);
            turns.insert(turn);
        } else {
            turns.update(null, Wrappers.<LearningTurn>update().eq("id", turn.getId()).set("status", "RUNNING")
                    .set("error_message", null).set("processing_token", token)
                    .set("lease_until", now.plusSeconds(properties.leaseSeconds())).set("updated_at", now)
                    .set("attempt_count", turn.getAttemptCount() + 1));
            turn = turns.selectById(turn.getId());
        }
        session.setActiveTurnId(turn.getId()); session.setUpdatedAt(now); sessions.updateById(session);
        if (contexts.selectById(sessionId) == null) {
            LearningContext context = new LearningContext();
            context.setSessionId(sessionId); context.setUserId(userId);
            context.setAgentStateJson(initializer.initialState(session, point));
            context.setCompressionStrategy(properties.compressionStrategy()); context.setUpdatedAt(now); contexts.insert(context);
        }
        learning.beginPoint(point);
        return new Claim(turn, true);
    }

    @Transactional
    public LearningContext configureStrategy(Long userId, Long sessionId, String strategy) {
        if (!List.of("NONE", "THRESHOLD", "WHOLE_HISTORY", "LOCAL").contains(strategy)) { throw new BusinessException("未知压缩策略"); }
        LearningSession session = lockSession(userId, sessionId);
        if (turns.selectCount(Wrappers.<LearningTurn>query().eq("session_id", sessionId)) > 0) {
            throw new BusinessException("压缩策略必须在第一条消息前冻结");
        }
        KnowledgePoint point = learning.requireActivePoint(session);
        if (!"NEW".equals(point.getStatus()) || point.getSequenceNo() != 1) { throw new BusinessException("只能为未开始的会话设置实验策略"); }
        LearningContext context = contexts.selectById(sessionId);
        if (context == null) {
            context = new LearningContext(); context.setSessionId(sessionId); context.setUserId(userId);
            context.setAgentStateJson(initializer.initialState(session, point)); context.setCompressionStrategy(strategy);
            context.setUpdatedAt(LocalDateTime.now()); contexts.insert(context);
        } else {
            context.setCompressionStrategy(strategy); context.setUpdatedAt(LocalDateTime.now()); contexts.updateById(context);
        }
        return context;
    }

    public LearningTurn require(Long userId, Long sessionId, Long turnId) {
        learning.requireSession(userId, sessionId);
        LearningTurn turn = turns.selectOne(Wrappers.<LearningTurn>query().eq("id", turnId).eq("session_id", sessionId).eq("user_id", userId));
        if (turn == null) { throw new BusinessException(404, "学习回合不存在"); }
        return turn;
    }

    public List<LearningTurn> list(Long userId, Long sessionId) {
        learning.requireSession(userId, sessionId);
        return turns.selectList(Wrappers.<LearningTurn>query().eq("session_id", sessionId).eq("user_id", userId).orderByAsc("id"));
    }

    public LearningContext context(Long userId, Long sessionId) {
        learning.requireSession(userId, sessionId);
        LearningContext context = contexts.selectOne(Wrappers.<LearningContext>query().eq("session_id", sessionId).eq("user_id", userId));
        if (context == null) { throw new BusinessException("学习上下文尚未初始化"); }
        return context;
    }

    @Transactional
    public LearningTurn commitModel(LearningTurn expected, LearningConversationGateway.Result result) {
        LearningSession session = fence(expected);
        LearningTurn turn = turns.selectById(expected.getId());
        if (!"MODEL".equals(turn.getPhase())) { return turn; }
        KnowledgePoint point = learning.requireActivePoint(session);
        if (!point.getId().equals(turn.getKnowledgePointId())) { throw new BusinessException(409, "当前知识点已变化"); }
        LearningTurnIntent intent = result.intent();
        Object artifact = Map.of("type", "QUESTION");
        if (intent.action() == null) {
            learning.clearFailure(session, point);
        } else {
            switch (intent.action()) {
                case EXPLANATION -> {
                    learning.saveExplanationAndAdvance(session, point, result.answer());
                    artifact = Map.of("type", "EXPLANATION", "knowledgePointId", point.getId());
                }
                case QUIZ -> {
                    Quiz quiz = learning.saveQuizAndAdvance(session, point, json(intent.questions()));
                    artifact = Map.of("type", "QUIZ", "quizId", quiz.getId(), "knowledgePointId", point.getId(),
                            "questions", java.util.stream.IntStream.range(0,intent.questions().size()).mapToObj(i -> {
                                QuizQuestionDraft q = intent.questions().get(i);
                                return Map.of("questionIndex", i, "question", q.question(), "options", q.options(), "sourceChunkId", q.sourceChunkId());
                            }).toList());
                }
                case GRADE -> {
                    Quiz quiz = learning.requireQuiz(point);
                    learning.saveQuizResultAndAdvance(session, quiz, point, json(intent.answers()), intent.score(), json(intent.feedback()));
                    artifact = Map.of("type", "GRADE", "quizId", quiz.getId(), "score", intent.score(), "feedback", intent.feedback());
                }
                case CARDS -> {
                    List<ReviewCard> written = cards.replaceDrafts(session.getUserId(), point.getId(), session.getKnowledgeBaseId(),
                            intent.cards().stream().map(c -> new ReviewCardService.Draft(c.front(), c.back(), c.sourceChunkId())).toList());
                    artifact = Map.of("type", "CARDS", "knowledgePointId", point.getId(), "cards", written.stream().map(c ->
                            Map.of("id", c.getId(), "front", c.getFront(), "back", c.getBack(), "sourceChunkId", c.getSourceChunkId())).toList());
                }
                case PREPARE_CARDS -> artifact = Map.of("type", "PREPARE_CARDS");
            }
        }
        turn.setAssistantMessage(result.answer()); turn.setArtifactJson(json(artifact));
        turn.setContextDeltaJson(result.rawContextDelta()); turn.setPreparedContextJson(result.preparedContext());
        turn.setPhase("ARTIFACTS_COMMITTED"); turn.setUpdatedAt(LocalDateTime.now()); turns.updateById(turn);
        saveContext(turn, result.preparedContext());
        return turn;
    }

    @Transactional
    public LearningTurn complete(LearningTurn expected, String compactedContext) {
        LearningSession session = fence(expected);
        LearningTurn turn = turns.selectById(expected.getId());
        if (!"ARTIFACTS_COMMITTED".equals(turn.getPhase())) { throw new BusinessException("回合产物尚未提交"); }
        saveContext(turn, compactedContext);
        turns.update(null, Wrappers.<LearningTurn>update().eq("id", turn.getId()).set("status", "SUCCEEDED")
                .set("phase", "COMPLETED").set("assistant_message", turn.getAssistantMessage()).set("error_message", null)
                .set("processing_token", null).set("lease_until", null).set("updated_at", LocalDateTime.now()));
        sessions.update(null, Wrappers.<LearningSession>update().eq("id", session.getId()).set("active_turn_id", null)
                .set("error_message", null).set("updated_at", LocalDateTime.now()));
        return turns.selectById(turn.getId());
    }

    @Transactional
    public void renew(LearningTurn turn) { fence(turn); }

    @Transactional
    public void fail(LearningTurn expected, String message) {
        LearningSession session = lockSession(expected.getUserId(), expected.getSessionId());
        LearningTurn current = turns.selectById(expected.getId());
        if (!expected.getProcessingToken().equals(current.getProcessingToken()) || !"RUNNING".equals(current.getStatus())) { return; }
        turns.update(null, Wrappers.<LearningTurn>update().eq("id", current.getId()).set("status", "FAILED")
                .set("error_message", message).set("processing_token", null).set("lease_until", null).set("updated_at", LocalDateTime.now()));
        var update = Wrappers.<LearningSession>update().eq("id", session.getId()).set("error_message", message).set("updated_at", LocalDateTime.now());
        // Committed cards/context must finish recovery before any other turn can start.
        if ("MODEL".equals(current.getPhase())) { update.set("active_turn_id", null); }
        sessions.update(null, update);
    }

    public boolean isCards(LearningTurn turn) {
        try { return "CARDS".equals(mapper.readTree(turn.getArtifactJson()).path("type").asText()); }
        catch (JsonProcessingException e) { throw new IllegalStateException("读取学习产物失败", e); }
    }

    private LearningSession lockSession(Long userId, Long sessionId) {
        LearningSession session = sessions.selectOne(Wrappers.<LearningSession>query().eq("id", sessionId).eq("user_id", userId).last("FOR UPDATE"));
        if (session == null) { throw new BusinessException(404, "学习会话不存在"); }
        return session;
    }
    private LearningSession fence(LearningTurn turn) {
        LearningSession session = lockSession(turn.getUserId(), turn.getSessionId());
        if (!turn.getId().equals(session.getActiveTurnId())) { throw new BusinessException(409, "回合已失去会话执行权"); }
        LocalDateTime now = LocalDateTime.now();
        int updated = turns.update(null, Wrappers.<LearningTurn>update().eq("id", turn.getId()).eq("processing_token", turn.getProcessingToken())
                .eq("status", "RUNNING").gt("lease_until", now)
                .set("lease_until", now.plusSeconds(properties.leaseSeconds())).set("updated_at", now));
        if (updated != 1) { throw new BusinessException(409, "回合执行租约已失效，请恢复原请求"); }
        return session;
    }
    private void saveContext(LearningTurn turn, String state) {
        int updated = contexts.update(null, Wrappers.<LearningContext>update().eq("session_id", turn.getSessionId()).eq("user_id", turn.getUserId())
                .set("agent_state_json", state).set("updated_at", LocalDateTime.now()));
        if (updated != 1) { throw new BusinessException("学习上下文保存失败"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("学习回合序列化失败", e); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Claim(LearningTurn turn, boolean execute) { }
}
