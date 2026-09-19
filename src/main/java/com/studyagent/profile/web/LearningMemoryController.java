package com.studyagent.profile.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.profile.LearningMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning-memory")
@RequiredArgsConstructor
public class LearningMemoryController {
    private final LearningMemoryService memory;
    private final CurrentUserContext user;

    @GetMapping("/preferences")
    public ApiResponse<?> preferences() { return ApiResponse.ok(memory.preference(user.userId())); }

    @PutMapping("/preferences")
    public ApiResponse<?> save(@Valid @RequestBody Preferences request) {
        return ApiResponse.ok(memory.savePreference(user.userId(), request.explanationStyle(), request.learningGoal(), request.memoryEnabled()));
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam Long knowledgeBaseId) {
        return ApiResponse.ok(memory.list(user.userId(), knowledgeBaseId));
    }

    @PutMapping("/{id}/inclusion")
    public ApiResponse<?> include(@PathVariable Long id, @RequestParam Long knowledgeBaseId, @Valid @RequestBody Inclusion request) {
        memory.setIncluded(user.userId(), knowledgeBaseId, id, request.included());
        return ApiResponse.ok(null);
    }

    public record Preferences(@NotNull @Size(max = 300) String explanationStyle,
                              @NotNull @Size(max = 500) String learningGoal, @NotNull Boolean memoryEnabled) {}
    public record Inclusion(@NotNull Boolean included) {}
}
