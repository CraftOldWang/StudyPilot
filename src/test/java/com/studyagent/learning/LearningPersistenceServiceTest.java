package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.mapper.KnowledgePointMapper;
import com.studyagent.mapper.LearningPlanMapper;
import com.studyagent.mapper.LearningSessionMapper;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LearningPersistenceServiceTest {

    @Test
    void completingPointExplicitlyClearsPersistedSessionFailure() {
        LearningSessionMapper sessionMapper = mock(LearningSessionMapper.class);
        KnowledgePointMapper pointMapper = mock(KnowledgePointMapper.class);
        LearningPersistenceService service = new LearningPersistenceService(
                sessionMapper,
                mock(LearningPlanMapper.class),
                pointMapper,
                mock(QuizMapper.class),
                new ObjectMapper(), mock(com.studyagent.mapper.LearningPlanRunMapper.class),
                mock(com.studyagent.mapper.LearningPlanStageMapper.class), mock(com.studyagent.profile.LearningMemoryService.class));
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setErrorMessage("old failure");
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSessionId(10L);
        point.setSequenceNo(1);
        point.setStatus(KnowledgePointStatus.CARD_CONFIRMING.name());
        when(pointMapper.selectList(any())).thenReturn(List.of(point));

        service.completePoint(session, point);

        ArgumentCaptor<UpdateWrapper<LearningSession>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sessionMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("error_message", "updated_at", "active_knowledge_point_id");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue(null);
        assertThat(session.getErrorMessage()).isNull();
        assertThat(session.getActiveKnowledgePointId()).isNull();
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void successfulAdvanceExplicitlyClearsPersistedFailureFields() {
        LearningSessionMapper sessionMapper = mock(LearningSessionMapper.class);
        KnowledgePointMapper pointMapper = mock(KnowledgePointMapper.class);
        LearningPersistenceService service = new LearningPersistenceService(
                sessionMapper,
                mock(LearningPlanMapper.class),
                pointMapper,
                mock(QuizMapper.class),
                new ObjectMapper(), mock(com.studyagent.mapper.LearningPlanRunMapper.class),
                mock(com.studyagent.mapper.LearningPlanStageMapper.class), mock(com.studyagent.profile.LearningMemoryService.class));
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setErrorMessage("old failure");
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSessionId(10L);
        point.setStatus(KnowledgePointStatus.NEW.name());
        point.setErrorMessage("old failure");

        service.saveExplanationAndAdvance(session, point, "explanation");

        ArgumentCaptor<UpdateWrapper<LearningSession>> sessionUpdate = ArgumentCaptor.forClass(UpdateWrapper.class);
        ArgumentCaptor<UpdateWrapper<KnowledgePoint>> pointUpdate = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sessionMapper).update(isNull(), sessionUpdate.capture());
        verify(pointMapper).update(isNull(), pointUpdate.capture());
        assertThat(sessionUpdate.getValue().getSqlSet()).contains("error_message", "updated_at");
        assertThat(sessionUpdate.getValue().getParamNameValuePairs()).containsValue(null);
        assertThat(pointUpdate.getValue().getSqlSet()).contains("error_message", "updated_at");
        assertThat(pointUpdate.getValue().getParamNameValuePairs()).containsValue(null);
        assertThat(session.getErrorMessage()).isNull();
        assertThat(point.getErrorMessage()).isNull();
    }

    @Test
    void failureUpdateDoesNotRepersistMutatedBusinessState() {
        LearningSessionMapper sessionMapper = mock(LearningSessionMapper.class);
        KnowledgePointMapper pointMapper = mock(KnowledgePointMapper.class);
        LearningPersistenceService service = new LearningPersistenceService(
                sessionMapper,
                mock(LearningPlanMapper.class),
                pointMapper,
                mock(QuizMapper.class),
                new ObjectMapper(), mock(com.studyagent.mapper.LearningPlanRunMapper.class),
                mock(com.studyagent.mapper.LearningPlanStageMapper.class), mock(com.studyagent.profile.LearningMemoryService.class));
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setStatus("COMPLETED");
        session.setActiveKnowledgePointId(null);
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSessionId(10L);
        point.setStatus("COMPLETED");

        service.recordFailure(session, point, "database failure");

        ArgumentCaptor<UpdateWrapper<LearningSession>> sessionUpdate = ArgumentCaptor.forClass(UpdateWrapper.class);
        ArgumentCaptor<UpdateWrapper<KnowledgePoint>> pointUpdate = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sessionMapper).update(isNull(), sessionUpdate.capture());
        verify(pointMapper).update(isNull(), pointUpdate.capture());
        assertThat(sessionUpdate.getValue().getSqlSet())
                .contains("error_message", "updated_at")
                .doesNotContain("status", "active_knowledge_point_id");
        assertThat(pointUpdate.getValue().getSqlSet())
                .contains("error_message", "updated_at")
                .doesNotContain("status", "explanation", "completed_at");
        verify(sessionMapper, never()).updateById(any(LearningSession.class));
        verify(pointMapper, never()).updateById(any(KnowledgePoint.class));
    }
}
