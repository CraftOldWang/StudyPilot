package com.studyagent.learning.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.learning.LearningPersistenceService;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.mapper.ReviewCardMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.studyagent.learning.QuizFeedback;
import com.studyagent.learning.QuizQuestionDraft;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import com.studyagent.model.ReviewCard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LearningResponseAssembler {

    private final LearningPersistenceService learning;
    private final QuizMapper quizzes;
    private final ReviewCardMapper reviewCards;
    private final ObjectMapper objectMapper;

    public LearningSessionResponse session(Long userId, Long sessionId) {
        LearningSession session = learning.requireSession(userId, sessionId);
        List<KnowledgePoint> points = learning.listPoints(sessionId);
        KnowledgePoint focus = focus(session, points);
        Quiz quiz = focus == null ? null : quizzes.selectOne(new LambdaQueryWrapper<Quiz>()
                .eq(Quiz::getUserId, userId).eq(Quiz::getKnowledgePointId, focus.getId()));
        List<ReviewCard> cards = focus == null ? List.of() : reviewCards.selectList(new LambdaQueryWrapper<ReviewCard>()
                .eq(ReviewCard::getUserId, userId).eq(ReviewCard::getKnowledgePointId, focus.getId())
                .orderByAsc(ReviewCard::getCreatedAt));
        return new LearningSessionResponse(
                session.getId(),
                session.getLearningGoal(),
                session.getKnowledgeBaseId(),
                session.getStatus(),
                session.getErrorMessage(),
                session.getActiveKnowledgePointId() == null ? null : point(focus),
                points.stream().map(this::point).toList(),
                quiz == null ? null : quiz(quiz, focus.getId()),
                cards.stream().map(this::card).toList());
    }

    public LearningSessionResponse.QuizResponse quiz(Quiz quiz, Long knowledgePointId) {
        List<QuizQuestionDraft> questions = readList(quiz.getQuestionsJson(), new TypeReference<>() { });
        List<QuizFeedback> feedback = quiz.getFeedbackJson() == null ? null
                : readList(quiz.getFeedbackJson(), new TypeReference<>() { });
        return new LearningSessionResponse.QuizResponse(
                quiz.getId(),
                knowledgePointId,
                java.util.stream.IntStream.range(0, questions.size())
                        .mapToObj(index -> question(index, questions.get(index)))
                        .toList(),
                quiz.getScore(),
                feedback == null ? null : feedback.stream().map(this::feedback).toList());
    }

    public LearningSessionResponse.CardResponse card(ReviewCard card) {
        return new LearningSessionResponse.CardResponse(
                card.getId(), card.getFront(), card.getBack(), card.getSourceChunkId());
    }

    public LearningSessionResponse.FeedbackResponse feedback(QuizFeedback feedback) {
        return new LearningSessionResponse.FeedbackResponse(
                feedback.questionIndex(),
                feedback.correct(),
                feedback.correctAnswer(),
                feedback.explanation());
    }

    private KnowledgePoint focus(LearningSession session, List<KnowledgePoint> points) {
        if (session.getActiveKnowledgePointId() != null) {
            return points.stream()
                    .filter(point -> session.getActiveKnowledgePointId().equals(point.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("学习会话的活跃知识点不存在"));
        }
        return points.isEmpty() ? null : points.getLast();
    }

    private LearningSessionResponse.KnowledgePointResponse point(KnowledgePoint point) {
        return new LearningSessionResponse.KnowledgePointResponse(
                point.getId(),
                point.getSequenceNo(),
                point.getTopic(),
                readSubtopics(point.getSubtopicsJson()),
                point.getEstimatedMinutes(),
                point.getStatus(),
                point.getExplanation(),
                point.getErrorMessage(),
                point.getChapterId(),
                point.getChapterTitle(),
                point.getPriority(),
                point.getSourcesJson() == null ? List.of() : readSubtopics(point.getSourcesJson()), point.getOutlineNodeId());
    }

    private LearningSessionResponse.QuestionResponse question(int index, QuizQuestionDraft question) {
        return new LearningSessionResponse.QuestionResponse(
                index, question.question(), question.options(), question.sourceChunkId());
    }

    private List<String> readSubtopics(String json) {
        return readList(json, new TypeReference<>() { });
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("读取学习记录失败: " + ex.getOriginalMessage());
        }
    }
}
