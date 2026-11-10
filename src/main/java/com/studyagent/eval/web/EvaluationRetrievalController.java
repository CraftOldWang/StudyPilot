package com.studyagent.eval.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.config.RerankProperties;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.rag.retrieval.KnowledgeRetrievalService;
import com.studyagent.rag.retrieval.RetrievalMode;
import com.studyagent.rag.web.KnowledgeBaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("eval")
@RequestMapping("/api/eval/knowledge-bases/{knowledgeBaseId}")
@RequiredArgsConstructor
public class EvaluationRetrievalController {
    private final CurrentUserContext currentUser;
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeRetrievalService retrieval;

    @PostMapping("/context-comparison")
    public ApiResponse<KnowledgeRetrievalService.ContextComparison> compare(
            @PathVariable Long knowledgeBaseId, @Valid @RequestBody Request request) {
        Long userId = currentUser.userId();
        knowledgeBases.requireOwned(userId, knowledgeBaseId);
        return ApiResponse.ok(retrieval.compareContexts(userId, knowledgeBaseId, request.query(), request.topK(),
                request.mode(), Boolean.TRUE.equals(request.rerank()), request.rerankCandidates()));
    }

    public record Request(@NotBlank String query, @Min(1) @Max(20) Integer topK, RetrievalMode mode, Boolean rerank,
                          @Min(1) @Max(RerankProperties.MAX_CANDIDATES) Integer rerankCandidates) { }
}
