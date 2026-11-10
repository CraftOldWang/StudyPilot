package com.studyagent.ingest.upload;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.ingest.web.FileDedupCheckResponse;
import com.studyagent.ingest.web.InitMultipartUploadRequest;
import com.studyagent.ingest.web.InitMultipartUploadResponse;
import com.studyagent.ingest.web.MultipartUploadStatusResponse;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.model.FileRecord;
import com.studyagent.rag.web.KnowledgeBaseService;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FileUploadService {
    private final UploadPublicationService publication;
    private final NativeMultipartUploadService multipart;
    private final ObjectStorageService storage;
    private final RedissonClient redis;
    private final KnowledgeBaseService knowledgeBases;

    public FileDedupCheckResponse checkDuplicate(Long userId, Long kbId, String sha256) {
        knowledgeBases.requireOwned(userId, kbId);
        FileRecord file = publication.find(userId, kbId, UploadFileSupport.normalizeHash(sha256));
        return file == null ? new FileDedupCheckResponse(false, null, "NOT_FOUND")
                : new FileDedupCheckResponse(true, file.getId(), file.getStatus());
    }

    public UploadResultResponse uploadSingle(Long userId, Long kbId, MultipartFile file, Long logicalDocumentId) {
        knowledgeBases.requireOwned(userId, kbId);
        if (logicalDocumentId != null) publication.requireReplacementTarget(userId, kbId, logicalDocumentId);
        UploadFileSupport.validateType(file.getOriginalFilename(), file.getContentType());
        if (file.isEmpty()) throw new BusinessException("文件不能为空");
        String hash;
        try (InputStream input = file.getInputStream()) {
            hash = UploadFileSupport.hash(input).sha256();
        } catch (IOException ex) {
            throw new BusinessException("读取上传文件失败");
        }
        RLock lock = redis.getLock(UploadFileSupport.dedupLock(userId, kbId, hash));
        lock.lock();
        try {
            FileRecord existing = publication.find(userId, kbId, hash);
            String key = existing == null ? "files/uploads/" + UUID.randomUUID() + "/"
                    + UploadFileSupport.filename(file.getOriginalFilename()) : existing.getStorageKey();
            if (existing == null) {
                try (InputStream input = file.getInputStream()) {
                    storage.putObject(key, input, file.getSize(), UploadFileSupport.contentType(file.getContentType()));
                } catch (IOException ex) {
                    throw new BusinessException("读取上传文件失败");
                }
            }
            return publication.publish(userId, kbId, hash, key, file.getOriginalFilename(), file.getSize(),
                    file.getContentType(), null, logicalDocumentId);
        } finally {
            lock.unlock();
        }
    }

    public InitMultipartUploadResponse initMultipart(Long userId, InitMultipartUploadRequest request) {
        return multipart.init(userId, request);
    }

    public void uploadChunk(Long userId, Long sessionId, int chunkIndex, MultipartFile chunk) {
        multipart.uploadPart(userId, sessionId, chunkIndex, chunk);
    }

    public MultipartUploadStatusResponse multipartStatus(Long userId, Long sessionId) {
        return multipart.status(userId, sessionId);
    }

    public UploadResultResponse completeMultipart(Long userId, Long sessionId, Long kbId) {
        return multipart.complete(userId, sessionId, kbId);
    }

    public void cancelMultipart(Long userId, Long sessionId) {
        multipart.cancel(userId, sessionId);
    }
}
