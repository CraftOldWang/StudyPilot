package com.studyagent.ingest.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.mapper.LogicalDocumentMapper;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
import com.studyagent.model.LogicalDocument;
import com.studyagent.model.UploadSession;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UploadPublicationService {
    private final FileRecordMapper files;
    private final DocumentMapper documents;
    private final UploadSessionMapper sessions;
    private final DocumentIndexProducer producer;
    private final LogicalDocumentMapper logicalDocuments;

    public FileRecord find(Long userId, Long kbId, String hash) {
        return files.selectOne(fileQuery(userId, kbId, hash));
    }

    public void requireReplacementTarget(Long userId, Long kbId, Long logicalDocumentId) {
        requireActive(logicalDocuments.selectOne(logicalQuery(userId, kbId, logicalDocumentId)));
    }

    // The caller keeps its distributed lock until this short transaction commits.
    @Transactional
    public UploadResultResponse publish(Long userId, Long kbId, String hash, String key,
                                        String filename, long size, String contentType, Long uploadSessionId,
                                        Long logicalDocumentId) {
        Long previousDocumentId = logicalDocumentId == null ? null : requireActive(
                logicalDocuments.selectOne(logicalQuery(userId, kbId, logicalDocumentId).last("FOR UPDATE")));
        FileRecord file = find(userId, kbId, hash);
        boolean created = false;
        if (file == null) {
            file = new FileRecord();
            file.setUserId(userId);
            file.setKnowledgeBaseId(kbId);
            file.setFileHash(hash);
            file.setStorageKey(key);
            file.setFilename(UploadFileSupport.filename(filename));
            file.setFileSize(size);
            file.setStatus("STORED");
            file.setCreatedAt(LocalDateTime.now());
            try {
                files.insert(file);
                created = true;
            } catch (DuplicateKeyException ex) {
                // Current read sees the winning commit even under MySQL REPEATABLE READ.
                file = files.selectOne(fileQuery(userId, kbId, hash).last("FOR UPDATE"));
                if (file == null) throw ex;
            }
        }
        var documentQuery = documentQuery(userId, kbId, file.getId(), logicalDocumentId, previousDocumentId);
        Document document = previousDocumentId == null ? null : documents.selectById(previousDocumentId);
        if (document == null || !file.getId().equals(document.getFileRecordId())) {
            document = documents.selectOne(documentQuery);
        }
        if (document == null) {
            document = new Document();
            document.setId(IdWorker.getId());
            document.setUserId(userId);
            document.setKnowledgeBaseId(kbId);
            document.setFileRecordId(file.getId());
            document.setLogicalDocumentId(logicalDocumentId == null ? document.getId() : logicalDocumentId);
            document.setPreviousDocumentId(previousDocumentId);
            document.setTitle(UploadFileSupport.filename(filename));
            document.setContentType(UploadFileSupport.contentType(contentType));
            document.setPipelineStatus("STORED");
            document.setCreatedAt(LocalDateTime.now());
            document.setUpdatedAt(LocalDateTime.now());
            boolean documentCreated = false;
            try {
                documents.insert(document);
                documentCreated = true;
            } catch (DuplicateKeyException ex) {
                document = documents.selectOne(documentQuery.last("FOR UPDATE"));
                if (document == null) throw ex;
            }
            if (documentCreated) {
                if (logicalDocumentId == null) {
                    LogicalDocument logical = new LogicalDocument();
                    logical.setId(document.getId());
                    logical.setUserId(userId);
                    logical.setKnowledgeBaseId(kbId);
                    logicalDocuments.insert(logical);
                }
                producer.send(document.getId(), userId);
            }
        }
        if (uploadSessionId != null) {
            UploadSession completed = new UploadSession();
            completed.setId(uploadSessionId);
            completed.setStatus("COMPLETED");
            completed.setCompletedFileId(file.getId());
            completed.setCompletedDocumentId(document.getId());
            completed.setUpdatedAt(LocalDateTime.now());
            sessions.updateById(completed);
            sessions.setPhase(uploadSessionId, "COMPLETED", LocalDateTime.now());
        }
        return new UploadResultResponse(file.getId(), document.getId(), created ? "UPLOADED" : "DUPLICATED");
    }

    private LambdaQueryWrapper<FileRecord> fileQuery(Long userId, Long kbId, String hash) {
        return new LambdaQueryWrapper<FileRecord>().eq(FileRecord::getUserId, userId)
                .eq(FileRecord::getKnowledgeBaseId, kbId).eq(FileRecord::getFileHash, hash);
    }

    private LambdaQueryWrapper<Document> documentQuery(Long userId, Long kbId, Long fileId, Long logicalId,
                                                       Long previousDocumentId) {
        var query = new LambdaQueryWrapper<Document>().eq(Document::getUserId, userId)
                .eq(Document::getKnowledgeBaseId, kbId).eq(Document::getFileRecordId, fileId);
        return logicalId == null ? query.isNull(Document::getPreviousDocumentId)
                : query.eq(Document::getLogicalDocumentId, logicalId).eq(Document::getPreviousDocumentId, previousDocumentId);
    }

    private LambdaQueryWrapper<LogicalDocument> logicalQuery(Long userId, Long kbId, Long logicalId) {
        return new LambdaQueryWrapper<LogicalDocument>().eq(LogicalDocument::getId, logicalId)
                .eq(LogicalDocument::getUserId, userId).eq(LogicalDocument::getKnowledgeBaseId, kbId);
    }

    private Long requireActive(LogicalDocument document) {
        if (document == null) throw new BusinessException(404, "逻辑文档不存在或不属于当前资料库");
        if (document.getActiveDocumentId() == null) throw new BusinessException(409, "文档尚无可替换的有效版本");
        return document.getActiveDocumentId();
    }
}
