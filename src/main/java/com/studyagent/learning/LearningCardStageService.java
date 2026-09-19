package com.studyagent.learning;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.identity.IdentityScope;
import com.studyagent.mapper.*;
import com.studyagent.model.*;
import com.studyagent.review.AnkiExportService;
import io.agentscope.core.state.AgentState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class LearningCardStageService {
    private final LearningSessionMapper sessions;
    private final KnowledgePointMapper points;
    private final LearningContextMapper contexts;
    private final ReviewCardMapper cards;
    private final LearningPersistenceService learning;
    private final LearningConversationCompactor compactor;
    private final AnkiExportService anki;
    private final TransactionTemplate transactions;
    private final IdentityScope identity;
    private final com.studyagent.config.DemoProperties demo;
    @jakarta.annotation.Resource(name = "learningConversationExecutor")
    private ExecutorService executor;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> jobs = new ConcurrentHashMap<>();

    public void begin(LearningSession session, LearningTurn turn, String input) {
        transactions.executeWithoutResult(tx -> {
            lock(session.getUserId(), session.getId());
            var point = learning.requireActivePoint(session);
            if (!"FEEDBACK".equals(point.getStatus())) { throw new BusinessException("当前不在练习后答疑阶段"); }
            points.update(null, Wrappers.<KnowledgePoint>update().eq("id", point.getId()).set("status", "CARD_GENERATING"));
            contexts.update(null, Wrappers.<LearningContext>update().eq("session_id", session.getId())
                    .set("pending_point_id", point.getId()).set("pending_summary_input", input)
                    .set("pending_summary_text", null).set("pending_summary_status", "PENDING").set("pending_summary_error", null));
        });
        startSummary(session);
    }

    private synchronized CompletableFuture<Void> startSummary(LearningSession session) {
        var running = jobs.get(session.getId());
        if (running != null && !running.isDone()) { return running; }
        var context = contexts.selectById(session.getId());
        if ("NONE".equals(context.getCompressionStrategy())) { return CompletableFuture.completedFuture(null); }
        if ("READY".equals(context.getPendingSummaryStatus())) { return CompletableFuture.completedFuture(null); }
        if (context.getPendingPointId() == null || context.getPendingSummaryInput() == null) {
            throw new BusinessException("缺少写卡前的学习上下文，不能确认此旧版知识点");
        }
        contexts.update(null, Wrappers.<LearningContext>update().eq("session_id", session.getId())
                .set("pending_summary_status", "RUNNING").set("pending_summary_error", null));
        var future = CompletableFuture.runAsync(() -> {
            try (var ignored = identity.bind(session.getUserId())) {
                String summary = compactor.summarizePoint(session, context.getPendingPointId(), context.getPendingSummaryInput());
                contexts.update(null, Wrappers.<LearningContext>update().eq("session_id", session.getId())
                        .eq("pending_point_id", context.getPendingPointId()).set("pending_summary_text", summary)
                        .set("pending_summary_status", "READY"));
            } catch (RuntimeException error) {
                contexts.update(null, Wrappers.<LearningContext>update().eq("session_id", session.getId())
                        .eq("pending_point_id", context.getPendingPointId()).set("pending_summary_status", "FAILED")
                        .set("pending_summary_error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }, executor);
        jobs.put(session.getId(), future);
        return future;
    }

    public void edit(Long userId, Long sessionId, Long pointId, List<Edit> edits) {
        transactions.executeWithoutResult(tx -> {
            var session = lock(userId, sessionId);
            requireIdle(session);
            var point = requirePoint(session, pointId);
            if (!"CARD_GENERATING".equals(point.getStatus())) { throw new BusinessException("只有待确认卡片可以编辑"); }
            var existing = list(userId, pointId);
            if (edits == null || edits.isEmpty() || edits.size() > 10 || edits.size() != existing.size()
                    || edits.stream().map(Edit::id).distinct().count() != existing.size()) {
                throw new BusinessException("请保存当前整组卡片，数量为1–10张");
            }
            for (Edit edit : edits) {
                var card = existing.stream().filter(c -> c.getId().equals(edit.id())).findFirst()
                        .orElseThrow(() -> new BusinessException("卡片草稿已变化，请刷新后编辑"));
                if (edit.front() == null || edit.front().isBlank() || edit.back() == null || edit.back().isBlank()) {
                    throw new BusinessException("卡片正面和背面不能为空");
                }
                card.setFront(edit.front().trim()); card.setBack(edit.back().trim()); cards.updateById(card);
            }
        });
    }

    public void confirm(Long userId, Long sessionId, Long pointId) {
        var session = transactions.execute(tx -> {
            var locked = lock(userId, sessionId);
            var point = requirePoint(locked, pointId);
            if ("COMPLETED".equals(point.getStatus())) { return locked; }
            requireIdle(locked);
            if (!Objects.equals(locked.getActiveKnowledgePointId(), pointId)
                    || !List.of("CARD_GENERATING", "CARD_CONFIRMING").contains(point.getStatus()) || list(userId, pointId).isEmpty()) {
                throw new BusinessException("当前知识点没有可以确认的卡片");
            }
            points.update(null, Wrappers.<KnowledgePoint>update().eq("id", pointId).set("status", "CARD_CONFIRMING"));
            return locked;
        });
        if ("COMPLETED".equals(points.selectById(pointId).getStatus())) { return; }
        startSummary(session).join();
        var prepared = contexts.selectById(sessionId);
        boolean compactPoint = !"NONE".equals(prepared.getCompressionStrategy());
        if (compactPoint && !"READY".equals(prepared.getPendingSummaryStatus())) {
            throw new BusinessException("学习摘要未完成：" + prepared.getPendingSummaryError() + "；可重试确认，原上下文保留");
        }
        if (!demo.enabled()) {
            for (var card : list(userId, pointId)) { anki.export(userId, card.getId()); }
        }
        transactions.executeWithoutResult(tx -> {
            var locked = lock(userId, sessionId);
            var point = requirePoint(locked, pointId);
            if ("COMPLETED".equals(point.getStatus())) { return; }
            var current = contexts.selectById(sessionId);
            var state = AgentState.fromJsonString(current.getAgentStateJson());
            var reduced = compactPoint ? LearningCompactionPolicy.replacePoint(state.getContext(), pointId,
                    LearningContextMessages.summary("知识点 " + point.getSequenceNo() + "：" + point.getTopic() + "\n"
                            + current.getPendingSummaryText(), pointId, "POINT")) : state.getContext();
            LearningContextMessages.requirePaired(reduced);
            contexts.update(null, Wrappers.<LearningContext>update().eq("session_id", sessionId)
                    .set("agent_state_json", LearningContextMessages.stateJson(state.getUserId(), state.getSessionId(), reduced))
                    .set("pending_point_id", null).set("pending_summary_input", null).set("pending_summary_text", null)
                    .set("pending_summary_status", null).set("pending_summary_error", null).set("updated_at", LocalDateTime.now()));
            learning.completePoint(locked, point);
        });
        jobs.remove(sessionId);
    }

    public List<ReviewCard> drafts(Long userId, Long pointId) { return list(userId, pointId); }

    private List<ReviewCard> list(Long userId, Long pointId) {
        return cards.selectList(Wrappers.<ReviewCard>query().eq("user_id", userId).eq("knowledge_point_id", pointId).orderByAsc("id"));
    }
    private LearningSession lock(Long userId, Long sessionId) {
        var session = sessions.selectOne(Wrappers.<LearningSession>query().eq("id", sessionId).eq("user_id", userId).last("FOR UPDATE"));
        if (session == null) { throw new BusinessException(404, "学习会话不存在"); }
        return session;
    }
    private KnowledgePoint requirePoint(LearningSession session, Long pointId) {
        var point = points.selectById(pointId);
        if (point == null || !session.getId().equals(point.getSessionId()) || !session.getUserId().equals(point.getUserId())) {
            throw new BusinessException(404, "知识点不属于当前会话");
        }
        return point;
    }
    private void requireIdle(LearningSession session) {
        if (session.getActiveTurnId() != null) { throw new BusinessException("请先等待当前对话完成"); }
    }
    public record Edit(Long id, String front, String back) { }
}
