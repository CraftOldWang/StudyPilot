package com.studyagent.rag.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.model.Document;
import com.studyagent.model.KnowledgeBase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final CurrentUserContext currentUserContext;

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody NameRequest request) {
        return ApiResponse.ok(toResponse(knowledgeBaseService.create(currentUserContext.userId(), request.name())));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> list() {
        return ApiResponse.ok(knowledgeBaseService.list(currentUserContext.userId()).stream()
                .map(this::toResponse)
                .toList());
    }

    @PatchMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBaseResponse> rename(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody NameRequest request
    ) {
        return ApiResponse.ok(toResponse(knowledgeBaseService.rename(
                currentUserContext.userId(), knowledgeBaseId, request.name())));
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<List<DocumentResponse>> documents(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.ok(knowledgeBaseService.listDocuments(
                        currentUserContext.userId(), knowledgeBaseId).stream()
                .map(this::toResponse)
                .toList());
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt());
    }

    private DocumentResponse toResponse(KnowledgeBaseService.DocumentListing listing) {
        Document document = listing.document();
        return new DocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFileRecordId(),
                document.getTitle(),
                document.getContentType(),
                document.getPipelineStatus(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getLogicalDocumentId(),
                document.getPreviousDocumentId(),
                listing.active());
    }

    public record NameRequest(@NotBlank String name) {
    }

    public record KnowledgeBaseResponse(
            Long id,
            String name,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record DocumentResponse(
            Long id,
            Long knowledgeBaseId,
            Long fileRecordId,
            String title,
            String contentType,
            String pipelineStatus,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long logicalDocumentId,
            Long previousDocumentId,
            @JsonProperty("isActive") boolean active
    ) {
    }
}
