package com.studyagent.learning.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.learning.LearningFlowService;
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

    private final LearningFlowService flowService;
    private final ObjectMapper objectMapper;

    public LearningSessionResponse session(Long userId, Long sessionId) {
        LearningSession session = flowService.loadSession(userId, sessionId);
        List<KnowledgePoint> points = flowService.listPoints(sessionId);
        KnowledgePoint focus = focus(session, points);
        Quiz quiz = focus == null ? null : flowService.findQuiz(focus);
        List<ReviewCard> cards = focus == null ? List.of() : flowService.existingCards(focus.getId());
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
        List<QuizQuestionDraft> questions = flowService.readQuestions(quiz.getQuestionsJson());
        List<QuizFeedback> feedback = flowService.readFeedback(quiz.getFeedbackJson());
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
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new BusinessException("读取知识点子主题失败: " + ex.getMessage());
        }
    }
}
