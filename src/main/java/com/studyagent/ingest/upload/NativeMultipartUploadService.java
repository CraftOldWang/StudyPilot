package com.studyagent.ingest.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.UploadProperties;
import com.studyagent.ingest.storage.MultipartUploadPart;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.ingest.web.InitMultipartUploadRequest;
import com.studyagent.ingest.web.InitMultipartUploadResponse;
import com.studyagent.ingest.web.MultipartUploadStatusResponse;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.mapper.UploadPartMapper;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.model.FileRecord;
import com.studyagent.model.UploadPart;
import com.studyagent.model.UploadSession;
import com.studyagent.rag.web.KnowledgeBaseService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class NativeMultipartUploadService {
    // A cache miss must remain absent until status() rebuilds the entire bitmap from durable parts.
    private static final DefaultRedisScript<Long> MARK_UPLOADED = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('SETBIT', KEYS[1], ARGV[1], 1)
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final UploadSessionMapper sessions;
    private final UploadPartMapper parts;
    private final UploadPublicationService publication;
    private final ObjectStorageService storage;
    private final RedissonClient locks;
    private final StringRedisTemplate redis;
    private final KnowledgeBaseService knowledgeBases;
    private final UploadProperties properties;

    public InitMultipartUploadResponse init(Long userId, InitMultipartUploadRequest request) {
        knowledgeBases.requireOwned(userId, request.knowledgeBaseId());
        UploadFileSupport.validateType(request.filename(), request.contentType());
        String hash = UploadFileSupport.normalizeHash(request.sha256());
        validateLayout(request);
        RLock lock = locks.getLock(UploadFileSupport.dedupLock(userId, request.knowledgeBaseId(), hash));
        lock.lock();
        try {
            FileRecord existing = publication.find(userId, request.knowledgeBaseId(), hash);
            if (existing != null) {
                UploadResultResponse result = publication.publish(userId, request.knowledgeBaseId(), hash,
                        existing.getStorageKey(), existing.getFilename(), existing.getFileSize(), request.contentType(), null);
                return new InitMultipartUploadResponse(null, true, result.fileId(), result.documentId(),
                        "DUPLICATED", 0, request.totalChunks());
            }
            UploadSession session = sessions.selectActiveSession(userId, request.knowledgeBaseId(), hash, LocalDateTime.now());
            if (session != null && (session.getFileSize() != request.fileSize()
                    || session.getChunkSize() != request.chunkSize() || session.getTotalChunks() != request.totalChunks())) {
                throw new BusinessException("已有上传会话的分片布局不同，请先取消该上传");
            }
            if (session == null) {
                session = new UploadSession();
                session.setUserId(userId);
                session.setKnowledgeBaseId(request.knowledgeBaseId());
                session.setFilename(UploadFileSupport.filename(request.filename()));
                session.setContentType(UploadFileSupport.contentType(request.contentType()));
                session.setFileHash(hash);
                session.setFileSize(request.fileSize());
                session.setChunkSize(request.chunkSize());
                session.setTotalChunks(request.totalChunks());
                session.setUploadedChunks(0);
                session.setStatus("INITIALIZING");
                session.setStorageKey("files/uploads/" + UUID.randomUUID() + "/" + session.getFilename());
                session.setCreatedAt(LocalDateTime.now());
                session.setUpdatedAt(LocalDateTime.now());
                session.setExpiresAt(LocalDateTime.now().plus(properties.sessionTtl()));
                sessions.insert(session);
            }
            if ("INITIALIZING".equals(session.getStatus())) {
                try {
                    String recoveredId = storage.findMultipartUpload(session.getStorageKey());
                    session.setStorageUploadId(recoveredId == null
                            ? storage.initiateMultipartUpload(session.getStorageKey(), session.getContentType()) : recoveredId);
                    session.setStatus("UPLOADING");
                    session.setUpdatedAt(LocalDateTime.now());
                    sessions.updateById(session);
                    sessions.setPhase(session.getId(), "UPLOADING", LocalDateTime.now());
                } catch (RuntimeException ex) {
                    saveError(session, "INITIALIZING", ex);
                    throw ex;
                }
            }
            int uploaded = parts.listParts(session.getId()).size();
            return new InitMultipartUploadResponse(session.getId(), false, null, null,
                    session.getStatus(), uploaded, session.getTotalChunks());
        } finally {
            lock.unlock();
        }
    }

    public void uploadPart(Long userId, Long sessionId, int index, MultipartFile chunk) {
        // Readers upload concurrently; completion/cancellation takes the write lock after all readers finish.
        RLock sessionLock = locks.getReadWriteLock(sessionLockKey(sessionId)).readLock();
        sessionLock.lock();
        RLock partLock = locks.getLock("lock:upload:part:" + sessionId + ":" + index);
        try {
            UploadSession session = required(userId, sessionId);
            requireWritable(session);
            validatePart(session, index, chunk.getSize());
            partLock.lock();
            try {
                UploadPart existing = parts.selectOne(new LambdaQueryWrapper<UploadPart>()
                        .eq(UploadPart::getUploadSessionId, sessionId).eq(UploadPart::getChunkIndex, index));
                if (existing == null) {
                    MultipartUploadPart stored;
                    try (InputStream input = chunk.getInputStream()) {
                        stored = storage.uploadPart(session.getStorageKey(), session.getStorageUploadId(), index + 1, input, chunk.getSize());
                    } catch (IOException ex) {
                        throw new BusinessException("读取分片失败: " + index);
                    }
                    UploadPart part = new UploadPart();
                    part.setUserId(userId);
                    part.setUploadSessionId(sessionId);
                    part.setChunkIndex(index);
                    part.setEtag(stored.eTag());
                    part.setByteSize(chunk.getSize());
                    part.setCreatedAt(LocalDateTime.now());
                    parts.insert(part);
                }
                markUploaded(session, index);
            } catch (RuntimeException ex) {
                saveError(session, "PART/" + index, ex);
                throw ex;
            } finally {
                partLock.unlock();
            }
        } finally {
            sessionLock.unlock();
        }
    }

    public MultipartUploadStatusResponse status(Long userId, Long sessionId) {
        UploadSession session = required(userId, sessionId);
        byte[] bitmap = progressBitmap(session);
        List<Integer> uploaded = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int index = 0; index < session.getTotalChunks(); index++) {
            if ((bitmap[index / 8] & (0x80 >>> (index % 8))) != 0) uploaded.add(index);
            else missing.add(index);
        }
        return new MultipartUploadStatusResponse(sessionId, session.getKnowledgeBaseId(), session.getFilename(),
                session.getFileHash(), session.getFileSize(), session.getChunkSize(), session.getTotalChunks(),
                uploaded.size(), uploaded, missing, session.getStatus(), session.getCompletedFileId(),
                session.getCompletedDocumentId(), session.getExpiresAt(), session.getErrorMessage());
    }

    public UploadResultResponse complete(Long userId, Long sessionId, Long kbId) {
        knowledgeBases.requireOwned(userId, kbId);
        RLock sessionLock = locks.getReadWriteLock(sessionLockKey(sessionId)).writeLock();
        sessionLock.lock();
        try {
            UploadSession session = required(userId, sessionId);
            if (!session.getKnowledgeBaseId().equals(kbId)) throw new BusinessException("完成上传的知识库与初始化不一致");
            if ("COMPLETED".equals(session.getStatus())) return completedResult(session);
            requireActive(session);
            List<UploadPart> stored = parts.listParts(sessionId);
            if (stored.size() != session.getTotalChunks()) throw new BusinessException("缺少分片，不能完成上传");
            for (int index = 0; index < stored.size(); index++) {
                if (stored.get(index).getChunkIndex() != index) throw new BusinessException("分片编号不连续");
            }
            try {
                if ("UPLOADING".equals(session.getStatus())) phase(session, "MERGING");
                if ("MERGING".equals(session.getStatus())) {
                    long started = System.nanoTime();
                    // A unique per-session key identifies an already completed S3 request after a lost response.
                    if (storage.objectSize(session.getStorageKey()) == null) {
                        storage.completeMultipartUpload(session.getStorageKey(), session.getStorageUploadId(), stored.stream()
                                .map(part -> new MultipartUploadPart(part.getChunkIndex() + 1, part.getEtag())).toList());
                    }
                    metric("MERGE", session, started);
                    phase(session, "VERIFYING");
                }
                if ("VERIFYING".equals(session.getStatus())) {
                    long started = System.nanoTime();
                    UploadFileSupport.VerifiedBytes verified;
                    try (InputStream input = storage.getObject(session.getStorageKey())) {
                        verified = UploadFileSupport.hash(input);
                    } catch (IOException ex) {
                        throw new BusinessException("读取合并对象进行校验失败");
                    }
                    metric("VERIFY", session, started);
                    if (verified.bytes() != session.getFileSize() || !verified.sha256().equals(session.getFileHash())) {
                        phase(session, "HASH_FAILED");
                        throw new BusinessException("完整文件大小或 SHA-256 与初始化声明不一致，请重新上传");
                    }
                    phase(session, "VERIFIED");
                }
                RLock dedupLock = locks.getLock(UploadFileSupport.dedupLock(userId, kbId, session.getFileHash()));
                dedupLock.lock();
                try {
                    UploadResultResponse result = publication.publish(userId, kbId, session.getFileHash(), session.getStorageKey(),
                            session.getFilename(), session.getFileSize(), session.getContentType(), sessionId);
                    if ("DUPLICATED".equals(result.status())) storage.deleteObject(session.getStorageKey());
                    return result;
                } finally {
                    dedupLock.unlock();
                }
            } catch (RuntimeException ex) {
                saveError(session, session.getStatus(), ex);
                throw ex;
            }
        } finally {
            sessionLock.unlock();
        }
    }

    public void cancel(Long userId, Long sessionId) {
        RLock lock = locks.getReadWriteLock(sessionLockKey(sessionId)).writeLock();
        lock.lock();
        try {
            UploadSession session = required(userId, sessionId);
            if ("CANCELLED".equals(session.getStatus())) return;
            if ("COMPLETED".equals(session.getStatus())) throw new BusinessException("上传已完成，不能取消");
            try {
                if (storage.objectSize(session.getStorageKey()) != null) {
                    storage.deleteObject(session.getStorageKey());
                } else {
                    String uploadId = session.getStorageUploadId() == null
                            ? storage.findMultipartUpload(session.getStorageKey()) : session.getStorageUploadId();
                    if (uploadId != null) storage.abortMultipartUpload(session.getStorageKey(), uploadId);
                }
                phase(session, "CANCELLED");
                redis.delete(bitmapKey(sessionId));
            } catch (RuntimeException ex) {
                saveError(session, "CANCELLING", ex);
                throw ex;
            }
        } finally {
            lock.unlock();
        }
    }

    private UploadSession required(Long userId, Long sessionId) {
        UploadSession session = sessions.selectOne(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getId, sessionId).eq(UploadSession::getUserId, userId));
        if (session == null) throw new BusinessException("上传会话不存在");
        knowledgeBases.requireOwned(userId, session.getKnowledgeBaseId());
        if (session.getStorageKey() == null && !"COMPLETED".equals(session.getStatus())) {
            throw new BusinessException("旧版上传会话不支持原生续传，请重新选择文件上传");
        }
        return session;
    }

    private void requireWritable(UploadSession session) {
        requireActive(session);
        if (!"UPLOADING".equals(session.getStatus())) throw new BusinessException("上传正在合并或校验，不能修改分片");
    }

    private void requireActive(UploadSession session) {
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) throw new BusinessException("上传会话已过期，请取消后重新上传");
        if (!List.of("UPLOADING", "MERGING", "VERIFYING", "VERIFIED").contains(session.getStatus())) {
            throw new BusinessException("上传会话不可继续: " + session.getStatus());
        }
    }

    private void validateLayout(InitMultipartUploadRequest request) {
        if (request.fileSize() < 1 || request.fileSize() > properties.maxFileBytes()) throw new BusinessException("文件大小超出上传范围");
        if (request.chunkSize() < 1 || request.chunkSize() > properties.maxChunkBytes()) throw new BusinessException("分片大小超出范围");
        if (request.totalChunks() < 1 || request.totalChunks() > 10_000
                || (request.fileSize() - 1) / request.chunkSize() + 1 != request.totalChunks()) {
            throw new BusinessException("totalChunks 与 fileSize/chunkSize 不匹配");
        }
        if (request.totalChunks() > 1 && request.chunkSize() < 5 * 1024 * 1024) throw new BusinessException("非末片至少需要 5 MiB");
    }

    private void validatePart(UploadSession session, int index, long size) {
        if (index < 0 || index >= session.getTotalChunks()) throw new BusinessException("分片序号超出范围");
        long expected = Math.min(session.getChunkSize(), session.getFileSize() - (long) index * session.getChunkSize());
        if (size != expected) throw new BusinessException("分片大小与初始化布局不一致");
    }

    private void markUploaded(UploadSession session, int index) {
        Duration ttl = Duration.between(LocalDateTime.now(), session.getExpiresAt());
        if (ttl.isNegative() || ttl.isZero()) return;
        redis.execute(MARK_UPLOADED, List.of(bitmapKey(session.getId())),
                Integer.toString(index), Long.toString(ttl.toMillis()));
    }

    private byte[] progressBitmap(UploadSession session) {
        byte[] key = bitmapKey(session.getId()).getBytes(StandardCharsets.UTF_8);
        byte[] cached = readBitmap(key);
        if (cached != null) return cached;

        // Reuse the session write lock so rebuilding cannot overwrite a concurrently saved part's bit.
        RLock lock = locks.getReadWriteLock(sessionLockKey(session.getId())).writeLock();
        lock.lock();
        try {
            cached = readBitmap(key);
            if (cached != null) return cached;
            byte[] rebuilt = new byte[(session.getTotalChunks() + 7) / 8];
            for (UploadPart part : parts.listParts(session.getId())) {
                int index = part.getChunkIndex();
                rebuilt[index / 8] |= (byte) (0x80 >>> (index % 8));
            }
            Duration ttl = Duration.between(LocalDateTime.now(), session.getExpiresAt());
            if (!ttl.isNegative() && !ttl.isZero()) {
                redis.execute((RedisCallback<Void>) connection -> {
                    connection.stringCommands().pSetEx(key, ttl.toMillis(), rebuilt);
                    return null;
                });
            }
            return rebuilt;
        } finally {
            lock.unlock();
        }
    }

    private byte[] readBitmap(byte[] key) {
        // Bitmap bytes are binary; decoding through StringRedisTemplate's UTF-8 value serializer loses bits.
        return redis.execute((RedisCallback<byte[]>) connection -> connection.stringCommands().get(key));
    }

    private void phase(UploadSession session, String phase) {
        sessions.setPhase(session.getId(), phase, LocalDateTime.now());
        session.setStatus(phase);
        session.setErrorMessage(null);
    }

    private void saveError(UploadSession session, String phase, RuntimeException error) {
        String message = phase + ": " + error.getClass().getSimpleName() + ": " + error.getMessage();
        sessions.setError(session.getId(), message.substring(0, Math.min(message.length(), 2048)), LocalDateTime.now());
    }

    private UploadResultResponse completedResult(UploadSession session) {
        return new UploadResultResponse(session.getCompletedFileId(), session.getCompletedDocumentId(), "COMPLETED");
    }

    private void metric(String phase, UploadSession session, long started) {
        log.info("UPLOAD_METRIC phase={} uploadSessionId={} bytes={} elapsedMillis={}", phase, session.getId(),
                session.getFileSize(), Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private String sessionLockKey(Long id) { return "lock:upload:session:" + id; }
    // Older keys were populated incrementally and may not represent a complete progress snapshot.
    private String bitmapKey(Long id) { return "upload:bitmap:v2:" + id; }
}
