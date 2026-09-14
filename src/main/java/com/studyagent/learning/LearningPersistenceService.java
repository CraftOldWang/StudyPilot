package com.studyagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.KnowledgePointMapper;
import com.studyagent.mapper.LearningPlanMapper;
import com.studyagent.mapper.LearningSessionMapper;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningPlan;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningPersistenceService {

    private final LearningSessionMapper sessionMapper;
    private final LearningPlanMapper planMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuizMapper quizMapper;
    private final ObjectMapper objectMapper;
    private final com.studyagent.mapper.LearningPlanRunMapper planningRuns;
    private final com.studyagent.mapper.LearningPlanStageMapper planningStages;
    private final KnowledgePointLifecycle lifecycle = new KnowledgePointLifecycle();

    @Transactional
    public LearningSession create(
            Long userId,
            Long knowledgeBaseId,
            String learningGoal,
            String agentScopeSessionId,
            List<LearningPlanItem> items) {
        return createRecords(userId, knowledgeBaseId, learningGoal, agentScopeSessionId, items, null);
    }

    @Transactional
    public LearningSession createFromPlanning(Long userId, Long runId) {
        return createFromPlanning(userId, runId, false);
    }

    @Transactional
    public LearningSession createFromPlanning(Long userId, Long runId, boolean newConversation) {
        var run = planningRuns.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.studyagent.model.LearningPlanRun>query()
                .eq("id", runId).eq("user_id", userId).last("FOR UPDATE"));
        if (run == null) { throw new BusinessException(404, "规划任务不存在"); }
        if (!newConversation && run.getSessionId() != null) { return requireSession(userId, run.getSessionId()); }
        if (!"SUCCEEDED".equals(run.getStatus())) { throw new BusinessException("规划完成后才能创建学习会话"); }
        var stage = planningStages.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.studyagent.model.LearningPlanStage>query()
                .eq("run_id", runId).eq("stage_key", "TASKS").eq("status", "SUCCEEDED")
                .orderByDesc("attempt_count").last("LIMIT 1"));
        if (stage == null) { throw new BusinessException("规划缺少已提交的任务阶段"); }
        PlanningData.Result result;
        try { result = objectMapper.readValue(stage.getOutputJson(), PlanningData.Result.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("读取规划任务失败", e); }
        List<LearningPlanItem> items = result.tasks().stream()
                .map(t -> new LearningPlanItem(t.topic(), t.subtopics(), t.estimatedMinutes())).toList();
        LearningSession session = createRecords(userId, run.getKnowledgeBaseId(), run.getLearningGoal(),
                java.util.UUID.randomUUID().toString(), items, result.tasks(), runId);
        run.setSessionId(session.getId());
        run.setUpdatedAt(LocalDateTime.now());
        planningRuns.updateById(run);
        return session;
    }

    @Transactional
    public LearningSession createEvaluationReplica(Long userId, Long sessionId) {
        var original = requireSession(userId, sessionId);
        var originalPlan = planMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<LearningPlan>query()
                .eq("session_id", sessionId).eq("user_id", userId));
        List<PlanningData.Task> source;
        try { source = objectMapper.readValue(originalPlan.getPlanJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (JsonProcessingException e) { throw new BusinessException("实验副本需要当前规划接口生成的大纲"); }
        // A replica shares the generated curriculum, but never the original progress or point IDs.
        var tasks = source.stream().map(t -> new PlanningData.Task(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                t.chapterId(), t.chapterTitle(), t.topic(), t.subtopics(), t.sourceChunkIds(), t.priority(),
                t.estimatedMinutes(), t.reason())).toList();
        var items = tasks.stream().map(t -> new LearningPlanItem(t.topic(), t.subtopics(), t.estimatedMinutes())).toList();
        return createRecords(userId, original.getKnowledgeBaseId(), original.getLearningGoal(),
                java.util.UUID.randomUUID().toString(), items, tasks);
    }

    private LearningSession createRecords(Long userId, Long knowledgeBaseId, String learningGoal,
            String agentScopeSessionId, List<LearningPlanItem> items, List<PlanningData.Task> tasks) {
        return createRecords(userId, knowledgeBaseId, learningGoal, agentScopeSessionId, items, tasks, null);
    }

    private LearningSession createRecords(Long userId, Long knowledgeBaseId, String learningGoal,
            String agentScopeSessionId, List<LearningPlanItem> items, List<PlanningData.Task> tasks, Long runId) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("学习计划不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setKnowledgeBaseId(knowledgeBaseId);
        session.setPlanRunId(runId);
        session.setLearningGoal(learningGoal);
        session.setAgentscopeSessionId(agentScopeSessionId);
        session.setStatus("ACTIVE");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        LearningPlan plan = new LearningPlan();
        plan.setSessionId(session.getId());
        plan.setUserId(userId);
        plan.setPlanJson(toJson(tasks == null ? items : tasks));
        plan.setCreatedAt(now);
        planMapper.insert(plan);

        var completed = runId == null ? java.util.Set.<Long>of() : sessionMapper.completedOutlineNodes(userId, runId);
        for (int index = 0; index < items.size(); index++) {
            LearningPlanItem item = items.get(index);
            KnowledgePoint point = new KnowledgePoint();
            if (tasks != null) {
                PlanningData.Task task = tasks.get(index);
                point.setId(runId == null ? task.knowledgePointId() : com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
                point.setOutlineNodeId(runId == null ? null : task.knowledgePointId());
                point.setChapterId(task.chapterId());
                point.setChapterTitle(task.chapterTitle());
                point.setPriority(task.priority());
                point.setSourcesJson(toJson(task.sourceChunkIds()));
            }
            point.setSessionId(session.getId());
            point.setUserId(userId);
            point.setSequenceNo(index + 1);
            point.setTopic(item.topic());
            point.setSubtopicsJson(toJson(item.subtopics()));
            point.setEstimatedMinutes(item.estimatedMinutes());
            point.setStatus(point.getOutlineNodeId() != null && completed.contains(point.getOutlineNodeId()) ? "COMPLETED" : "NEW");
            point.setCreatedAt(now);
            point.setUpdatedAt(now);
            knowledgePointMapper.insert(point);
            if (session.getActiveKnowledgePointId() == null && !"COMPLETED".equals(point.getStatus())) {
                session.setActiveKnowledgePointId(point.getId());
            }
        }
        if (session.getActiveKnowledgePointId() == null) session.setStatus("COMPLETED");
        sessionMapper.updateById(session);
        return session;
    }

    public LearningSession requireSession(Long userId, Long sessionId) {
        LearningSession session = sessionMapper.selectOne(new LambdaQueryWrapper<LearningSession>()
                .eq(LearningSession::getId, sessionId)
                .eq(LearningSession::getUserId, userId));
        if (session == null) {
            throw new BusinessException(404, "学习会话不存在: " + sessionId);
        }
        return session;
    }

    public KnowledgePoint requireActivePoint(LearningSession session) {
        if (session.getActiveKnowledgePointId() == null) {
            throw new BusinessException("学习会话没有活跃知识点");
        }
        KnowledgePoint point = knowledgePointMapper.selectById(session.getActiveKnowledgePointId());
        if (point == null || !session.getId().equals(point.getSessionId())) {
            throw new BusinessException("学习会话的活跃知识点不存在");
        }
        return point;
    }

    public List<KnowledgePoint> listPoints(Long sessionId) {
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getSessionId, sessionId)
                .orderByAsc(KnowledgePoint::getSequenceNo));
    }

    @Transactional
    public KnowledgePoint saveExplanationAndAdvance(
            LearningSession session, KnowledgePoint point, String explanation) {
        requireStatus(point, KnowledgePointStatus.NEW);
        point.setExplanation(explanation);
        KnowledgePoint advanced = advance(point, KnowledgePointStatus.EXPLAINING);
        clearSessionFailure(session);
        return advanced;
    }

    @Transactional
    public Quiz saveQuizAndAdvance(LearningSession session, KnowledgePoint point, String questionsJson) {
        requireStatus(point, KnowledgePointStatus.EXPLAINING);
        Quiz quiz = new Quiz();
        quiz.setUserId(point.getUserId());
        quiz.setSessionId(point.getSessionId());
        quiz.setKnowledgePointId(point.getId());
        quiz.setQuestionsJson(questionsJson);
        quiz.setCreatedAt(LocalDateTime.now());
        quizMapper.insert(quiz);
        advance(point, KnowledgePointStatus.QUIZZING);
        clearSessionFailure(session);
        return quiz;
    }

    public Quiz requireQuiz(KnowledgePoint point) {
        Quiz quiz = quizMapper.selectOne(new LambdaQueryWrapper<Quiz>()
                .eq(Quiz::getKnowledgePointId, point.getId()));
        if (quiz == null) {
            throw new BusinessException("当前知识点尚未生成测验");
        }
        return quiz;
    }

    @Transactional
    public void saveQuizResultAndAdvance(
            LearningSession session,
            Quiz quiz,
            KnowledgePoint point,
            String answersJson,
            int score,
            String feedbackJson) {
        requireStatus(point, KnowledgePointStatus.QUIZZING);
        quiz.setAnswersJson(answersJson);
        quiz.setScore(score);
        quiz.setFeedbackJson(feedbackJson);
        quiz.setAnsweredAt(LocalDateTime.now());
        quizMapper.updateById(quiz);
        advance(point, KnowledgePointStatus.FEEDBACK);
        clearSessionFailure(session);
    }

    @Transactional
    public void completePoint(LearningSession session, KnowledgePoint point) {
        requireStatus(point, KnowledgePointStatus.CARD_CONFIRMING);
        advance(point, KnowledgePointStatus.COMPLETED);
        List<KnowledgePoint> points = listPoints(session.getId());
        var completed = session.getPlanRunId() == null ? java.util.Set.<Long>of()
                : sessionMapper.completedOutlineNodes(session.getUserId(), session.getPlanRunId());
        KnowledgePoint next = points.stream()
                .filter(candidate -> candidate.getSequenceNo() > point.getSequenceNo())
                .filter(candidate -> !"COMPLETED".equals(candidate.getStatus()))
                .filter(candidate -> candidate.getOutlineNodeId() == null || !completed.contains(candidate.getOutlineNodeId()))
                .findFirst()
                .orElse(null);
        session.setActiveKnowledgePointId(next == null ? null : next.getId());
        session.setStatus(next == null ? "COMPLETED" : "ACTIVE");
        session.setErrorMessage(null);
        session.setUpdatedAt(LocalDateTime.now());
        // updateById skips null fields; the final point must explicitly clear the active pointer.
        sessionMapper.update(null, new UpdateWrapper<LearningSession>()
                .eq("id", session.getId()).eq("user_id", session.getUserId())
                .set("active_knowledge_point_id", session.getActiveKnowledgePointId()).set("status", session.getStatus())
                .set("error_message", null).set("updated_at", session.getUpdatedAt()));
    }

    @Transactional
    public void recordFailure(LearningSession session, KnowledgePoint point, String message) {
        String safeMessage = message == null || message.isBlank() ? "未知失败" : message;
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, new UpdateWrapper<LearningSession>()
                .eq("id", session.getId())
                .eq("user_id", session.getUserId())
                .set("error_message", safeMessage)
                .set("updated_at", now));
        if (point != null) {
            knowledgePointMapper.update(null, new UpdateWrapper<KnowledgePoint>()
                    .eq("id", point.getId())
                    .eq("session_id", session.getId())
                    .set("error_message", safeMessage)
                    .set("updated_at", now));
        }
    }

    @Transactional
    public void clearFailure(LearningSession session, KnowledgePoint point) {
        clearSessionFailure(session);
        if (point.getErrorMessage() != null) {
            point.setErrorMessage(null);
            point.setUpdatedAt(LocalDateTime.now());
            clearPointFailure(point);
        }
    }

    @Transactional
    public void beginPoint(KnowledgePoint point) {
        if (KnowledgePointStatus.NEW.name().equals(point.getStatus())) { advance(point, KnowledgePointStatus.EXPLAINING); }
    }

    private KnowledgePoint advance(KnowledgePoint point, KnowledgePointStatus target) {
        KnowledgePointStatus current;
        try {
            current = KnowledgePointStatus.valueOf(point.getStatus());
        } catch (RuntimeException ex) {
            throw new BusinessException("未知知识点状态: " + point.getStatus());
        }
        lifecycle.advance(current, target);
        boolean hadFailure = point.getErrorMessage() != null;
        point.setStatus(target.name());
        point.setErrorMessage(null);
        point.setUpdatedAt(LocalDateTime.now());
        if (target == KnowledgePointStatus.EXPLAINING && point.getStartedAt() == null) {
            point.setStartedAt(LocalDateTime.now());
        }
        if (target == KnowledgePointStatus.COMPLETED) {
            point.setCompletedAt(LocalDateTime.now());
        }
        knowledgePointMapper.updateById(point);
        if (hadFailure) {
            clearPointFailure(point);
        }
        return point;
    }

    private void requireStatus(KnowledgePoint point, KnowledgePointStatus expected) {
        if (point == null || !expected.name().equals(point.getStatus())) {
            throw new BusinessException("当前知识点必须处于 " + expected + " 状态");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("学习业务数据序列化失败: " + ex.getMessage());
        }
    }

    private void clearSessionFailure(LearningSession session) {
        if (session.getErrorMessage() != null) {
            session.setErrorMessage(null);
            session.setUpdatedAt(LocalDateTime.now());
            persistSessionFailureClear(session);
        }
    }

    private void persistSessionFailureClear(LearningSession session) {
        sessionMapper.update(null, new UpdateWrapper<LearningSession>()
                .eq("id", session.getId())
                .eq("user_id", session.getUserId())
                .set("error_message", null)
                .set("updated_at", session.getUpdatedAt()));
    }

    private void clearPointFailure(KnowledgePoint point) {
        knowledgePointMapper.update(null, new UpdateWrapper<KnowledgePoint>()
                .eq("id", point.getId())
                .eq("session_id", point.getSessionId())
                .set("error_message", null)
                .set("updated_at", point.getUpdatedAt()));
    }
}
