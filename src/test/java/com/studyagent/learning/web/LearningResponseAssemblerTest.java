package com.studyagent.learning.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.learning.LearningPersistenceService;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.mapper.ReviewCardMapper;
import static org.mockito.ArgumentMatchers.any;
import com.studyagent.learning.QuizFeedback;
import com.studyagent.learning.QuizQuestionDraft;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import com.studyagent.model.ReviewCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningResponseAssemblerTest {

    @Test
    void completedSessionRestoresLastQuizFeedbackAndCardsWithoutRegeneration() throws Exception {
        LearningPersistenceService flow = mock(LearningPersistenceService.class);
        QuizMapper quizzes = mock(QuizMapper.class);
        ReviewCardMapper cards = mock(ReviewCardMapper.class);
        ObjectMapper mapper = new ObjectMapper();
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setKnowledgeBaseId(2L);
        session.setLearningGoal("Java");
        session.setStatus("COMPLETED");
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSequenceNo(1);
        point.setTopic("Generics");
        point.setSubtopicsJson("[\"bounds\"]");
        point.setEstimatedMinutes(20);
        point.setStatus("COMPLETED");
        point.setChapterId(100L);
        point.setChapterTitle("Types");
        point.setPriority("HIGH");
        point.setSourcesJson("[\"source-20\"]");
        Quiz quiz = new Quiz();
        quiz.setId(30L);
        quiz.setScore(80);
        ReviewCard card = new ReviewCard();
        card.setId(40L);
        card.setFront("front");
        card.setBack("back");

        when(flow.requireSession(1L, 10L)).thenReturn(session);
        when(flow.listPoints(10L)).thenReturn(List.of(point));
        when(quizzes.selectOne(any())).thenReturn(quiz);
        when(cards.selectList(any())).thenReturn(List.of(card));
        quiz.setQuestionsJson(mapper.writeValueAsString(List.of(
                new QuizQuestionDraft("q", List.of("A", "B", "C", "D"), "A", "e", "c"))));
        quiz.setFeedbackJson(mapper.writeValueAsString(List.of(new QuizFeedback(0, true, "A", "e"))));
        LearningResponseAssembler assembler = new LearningResponseAssembler(flow, quizzes, cards, mapper);

        LearningSessionResponse response = assembler.session(1L, 10L);

        assertThat(response.activeKnowledgePoint()).isNull();
        assertThat(response.plan()).hasSize(1);
        assertThat(response.plan().getFirst().chapterId()).isEqualTo(100L);
        assertThat(response.plan().getFirst().chapterTitle()).isEqualTo("Types");
        assertThat(response.plan().getFirst().priority()).isEqualTo("HIGH");
        assertThat(response.plan().getFirst().sourceChunkIds()).containsExactly("source-20");
        assertThat(response.currentQuiz().score()).isEqualTo(80);
        assertThat(response.currentQuiz().feedback()).hasSize(1);
        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().getFirst().sourceChunkId()).isNull();
    }
}
