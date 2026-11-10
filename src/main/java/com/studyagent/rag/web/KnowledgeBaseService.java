package com.studyagent.rag.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.KnowledgeBaseMapper;
import com.studyagent.mapper.LogicalDocumentMapper;
import com.studyagent.model.Document;
import com.studyagent.model.KnowledgeBase;
import com.studyagent.model.LogicalDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final LogicalDocumentMapper logicalDocuments;

    @Transactional
    public KnowledgeBase create(Long userId, String name) {
        String normalizedName = normalizedName(name);
        if (knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, normalizedName)) > 0) {
            throw new BusinessException("知识库名称已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setUserId(userId);
        knowledgeBase.setName(normalizedName);
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    public List<KnowledgeBase> list(Long userId) {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .orderByDesc(KnowledgeBase::getUpdatedAt));
    }

    @Transactional
    public KnowledgeBase rename(Long userId, Long knowledgeBaseId, String name) {
        KnowledgeBase knowledgeBase = requireOwned(userId, knowledgeBaseId);
        knowledgeBase.setName(normalizedName(name));
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(knowledgeBase);
        return knowledgeBase;
    }

    public KnowledgeBase requireOwned(Long userId, Long knowledgeBaseId) {
        if (userId == null || knowledgeBaseId == null) {
            throw new BusinessException("用户和知识库不能为空");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getUserId, userId)
                .last("LIMIT 1"));
        if (knowledgeBase == null) {
            throw new BusinessException("知识库不存在或不属于当前用户");
        }
        return knowledgeBase;
    }

    @Transactional(readOnly = true)
    public List<DocumentListing> listDocuments(Long userId, Long knowledgeBaseId) {
        requireOwned(userId, knowledgeBaseId);
        var activeByLogicalId = new HashMap<Long, Long>();
        logicalDocuments.selectList(logicalScope(userId, knowledgeBaseId).select("id", "active_document_id"))
                .forEach(logical -> activeByLogicalId.put(logical.getId(), logical.getActiveDocumentId()));
        if (activeByLogicalId.isEmpty()) {
            return List.of();
        }
        List<Document> versions = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(Document::getCreatedAt, Document::getId));
        var listings = new ArrayList<DocumentListing>();
        var listedCandidates = new HashSet<Long>();
        for (Document document : versions) {
            Long logicalId = document.getLogicalDocumentId();
            if (!activeByLogicalId.containsKey(logicalId)) {
                continue;
            }
            Long activeId = activeByLogicalId.get(logicalId);
            if (document.getId().equals(activeId)) {
                listings.add(new DocumentListing(document, true));
            } else if (!"INDEXED".equals(document.getPipelineStatus())
                    && Objects.equals(document.getPreviousDocumentId(), activeId)
                    && listedCandidates.add(logicalId)) {
                listings.add(new DocumentListing(document, false));
            }
        }
        return listings;
    }

    public Set<Long> activeDocumentIds(Long userId, Long knowledgeBaseId) {
        return logicalDocuments.selectList(logicalScope(userId, knowledgeBaseId)
                        .select("active_document_id").isNotNull("active_document_id"))
                .stream().map(LogicalDocument::getActiveDocumentId).collect(Collectors.toSet());
    }

    private QueryWrapper<LogicalDocument> logicalScope(Long userId, Long knowledgeBaseId) {
        return Wrappers.<LogicalDocument>query().eq("user_id", userId).eq("knowledge_base_id", knowledgeBaseId);
    }

    public record DocumentListing(Document document, boolean active) { }

    private String normalizedName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("知识库名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 128) {
            throw new BusinessException("知识库名称不能超过 128 个字符");
        }
        return normalized;
    }
}
