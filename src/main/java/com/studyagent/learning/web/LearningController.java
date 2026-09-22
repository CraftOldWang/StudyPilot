package com.studyagent.learning.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.learning.*;
import com.studyagent.model.LearningTurn;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/sessions")
@RequiredArgsConstructor
public class LearningController {
    private final LearningResponseAssembler assembler;
    private final CurrentUserContext currentUserContext;
    private final LearningConversationService conversation;
    private final LearningTurnPersistence turns;
    private final LearningCardStageService cardStage;
    private final LearningToolDisplay toolDisplay;
    private final LearningCatalog catalog;

    @GetMapping
    public ApiResponse<List<LearningCatalog.SessionEntry>> list(@RequestParam Long knowledgeBaseId) {
        return ApiResponse.ok(catalog.sessions(currentUserContext.userId(), knowledgeBaseId));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<LearningSessionResponse> get(@PathVariable Long sessionId) {
        return ApiResponse.ok(assembler.session(currentUserContext.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<LearningTurnResponse> message(@PathVariable Long sessionId,
            @Valid @RequestBody LearningMessageRequest request) {
        var result = conversation.message(currentUserContext.userId(), sessionId,
                request.requestId() == null ? UUID.randomUUID().toString() : request.requestId(),
                request.message(), event -> { });
        return ApiResponse.ok(new LearningTurnResponse(result.getTraceId(), result.getAssistantMessage(),
                assembler.session(currentUserContext.userId(), sessionId), result));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<LearningTurn>> history(@PathVariable Long sessionId) {
        return ApiResponse.ok(turns.list(currentUserContext.userId(), sessionId));
    }

    @GetMapping("/{sessionId}/turns/{turnId}")
    public ApiResponse<LearningTurn> turn(@PathVariable Long sessionId, @PathVariable Long turnId) {
        return ApiResponse.ok(turns.require(currentUserContext.userId(), sessionId, turnId));
    }

    @GetMapping("/{sessionId}/turns/{turnId}/tools")
    public ApiResponse<?> tools(@PathVariable Long sessionId, @PathVariable Long turnId) {
        var turn = turns.require(currentUserContext.userId(), sessionId, turnId);
        return ApiResponse.ok(toolDisplay.history(currentUserContext.userId(), turn.getTraceId()));
    }

    @PostMapping("/{sessionId}/quiz/submit")
    public ApiResponse<QuizSubmissionResult> submit(@PathVariable Long sessionId,
            @Valid @RequestBody QuizSubmissionRequest request) {
        var turn = conversation.submitQuiz(currentUserContext.userId(), sessionId, request.answers());
        var session = assembler.session(currentUserContext.userId(), sessionId);
        var quiz = session.currentQuiz();
        return ApiResponse.ok(new QuizSubmissionResult(turn.getTraceId(), quiz.quizId(), quiz.score(),
                quiz.feedback(), session));
    }

    @PutMapping("/{sessionId}/points/{pointId}/cards")
    public ApiResponse<LearningSessionResponse> editCards(@PathVariable Long sessionId, @PathVariable Long pointId,
            @RequestBody List<LearningCardStageService.Edit> edits) {
        cardStage.edit(currentUserContext.userId(), sessionId, pointId, edits);
        return ApiResponse.ok(assembler.session(currentUserContext.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/points/{pointId}/cards/confirm")
    public ApiResponse<LearningSessionResponse> confirmCards(@PathVariable Long sessionId, @PathVariable Long pointId) {
        cardStage.confirm(currentUserContext.userId(), sessionId, pointId);
        return ApiResponse.ok(assembler.session(currentUserContext.userId(), sessionId));
    }

    public record QuizSubmissionResult(String traceId, Long quizId, int score,
            List<LearningSessionResponse.FeedbackResponse> feedback, LearningSessionResponse session) { }
}
