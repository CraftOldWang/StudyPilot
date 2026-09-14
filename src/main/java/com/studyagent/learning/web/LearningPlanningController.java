package com.studyagent.learning.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.learning.LearningPersistenceService;
import com.studyagent.learning.LearningPlanningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/plans")
@RequiredArgsConstructor
public class LearningPlanningController {
    private final LearningPlanningService planning;
    private final LearningPersistenceService persistence;
    private final LearningResponseAssembler assembler;
    private final CurrentUserContext user;
    private final com.studyagent.learning.LearningCatalog catalog;

    @GetMapping("/current")
    public ApiResponse<LearningPlanningService.View> current(@RequestParam Long knowledgeBaseId,
            @RequestParam(required = false) Long sessionId) {
        Long id = catalog.currentPlanId(user.userId(), knowledgeBaseId, sessionId);
        return ApiResponse.ok(id == null ? null : planning.view(user.userId(), id));
    }

    @PostMapping
    public ApiResponse<LearningPlanningService.View> create(@Valid @RequestBody Request request) {
        var run = planning.create(user.userId(), request.knowledgeBaseId(), request.learningGoal(),
                request.lessonDocumentIds(), request.exerciseDocumentIds(), request.targetPointCount());
        return ApiResponse.ok(planning.view(user.userId(), run.getId()));
    }

    @GetMapping("/{runId}")
    public ApiResponse<LearningPlanningService.View> get(@PathVariable Long runId) {
        return ApiResponse.ok(planning.view(user.userId(), runId));
    }

    @PostMapping("/{runId}/execute")
    public ApiResponse<LearningPlanningService.View> execute(@PathVariable Long runId) {
        return ApiResponse.ok(planning.execute(user.userId(), runId));
    }

    @PostMapping("/{runId}/session")
    public ApiResponse<LearningSessionResponse> session(@PathVariable Long runId,
            @RequestParam(defaultValue = "false") boolean newConversation) {
        var session = persistence.createFromPlanning(user.userId(), runId, newConversation);
        return ApiResponse.ok(assembler.session(user.userId(), session.getId()));
    }

    public record Request(@NotNull Long knowledgeBaseId, @NotBlank String learningGoal,
                          List<@NotNull Long> lessonDocumentIds, List<@NotNull Long> exerciseDocumentIds,
                          @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(30) Integer targetPointCount) { }
}
