package com.studyagent.rag.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.agent.integration.KnowledgeSearchAgentService;
import com.studyagent.config.RerankProperties;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.rag.retrieval.ContextMode;
import com.studyagent.rag.retrieval.KnowledgeRetrievalService;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import com.studyagent.rag.retrieval.RetrievalMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
@RequiredArgsConstructor
public class KnowledgeSearchController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeSearchAgentService knowledgeSearchAgentService;
    private final CurrentUserContext currentUserContext;

    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody SearchRequest request
    ) {
        Long userId = currentUserContext.userId();
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        return ApiResponse.ok(knowledgeRetrievalService.search(userId, knowledgeBaseId, request.query(), request.mode(),
                request.topK(), Boolean.TRUE.equals(request.rerank()), request.rerankCandidates(), request.contextMode()));
    }

    @PostMapping("/agent-search")
    public ApiResponse<KnowledgeSearchAgentService.AgentSearchResponse> agentSearch(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody SearchRequest request
    ) {
        Long userId = currentUserContext.userId();
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        return ApiResponse.ok(knowledgeSearchAgentService.answer(userId, knowledgeBaseId, request.query()));
    }

    public record SearchRequest(@NotBlank String query, RetrievalMode mode,
                                @Min(1) @Max(20) Integer topK, Boolean rerank,
                                @Min(1) @Max(RerankProperties.MAX_CANDIDATES) Integer rerankCandidates,
                                ContextMode contextMode) {
    }
}
