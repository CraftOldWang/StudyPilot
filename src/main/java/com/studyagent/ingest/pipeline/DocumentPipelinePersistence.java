package com.studyagent.ingest.pipeline;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.DocumentPipelineProperties;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.FileRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentPipelinePersistence {
    public static final List<String> ACTIVE_STATUSES = List.of(
            "PARSING", "TRANSCRIBING", "TRANSCRIBED", "PARSED", "CHUNKING", "CHUNKED", "EMBEDDING", "EMBEDDED", "INDEXING");
    private final DocumentMapper documentMapper;
    private final FileRecordMapper fileRecordMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentPipelineProperties properties;

    @Transactional
    public Document claim(Long documentId, boolean allowFailedRetry) {
        LocalDateTime now = LocalDateTime.now();
        var update = Wrappers.<Document>update().eq("id", documentId)
                .and(eligible -> {
                    eligible.eq("pipeline_status", "STORED");
                    if (allowFailedRetry) { eligible.or().eq("pipeline_status", "FAILED"); }
                    eligible.or(expired -> expired.in("pipeline_status", ACTIVE_STATUSES)
                            .and(lease -> lease.isNull("lease_until").or().le("lease_until", now)));
                })
                .set("pipeline_status", "PARSING").set("error_message", null)
                .set("processing_token", UUID.randomUUID().toString())
                .set("lease_until", now.plus(properties.leaseDuration()))
                .setSql("attempt_count = attempt_count + 1").set("updated_at", now);
        if (documentMapper.update(null, update) != 1) { return null; }
        return documentMapper.selectById(documentId);
    }

    public FileRecord loadFile(Long fileRecordId) { return fileRecordMapper.selectById(fileRecordId); }

    public List<DocumentChunk> loadChunks(Long documentId) {
        return documentChunkMapper.selectList(Wrappers.<DocumentChunk>query()
                .eq("document_id", documentId).orderByAsc("chunk_type", "chunk_index"));
    }

    @Transactional
    public void startStage(Document document, PipelineStatus stage) {
        writeOwned(document, update -> update.set("pipeline_status", stage.name()));
    }

    @Transactional
    public void renewLease(Document document) { writeOwned(document, update -> { }); }

    @Transactional
    public void markParsed(Document document, String key, String hash, String parserVersion) {
        writeOwned(document, update -> update.set("pipeline_status", "PARSED")
                .set("parser_version", parserVersion)
                .set("parsed_text_key", key).set("parsed_text_hash", hash)
                .set("chunker_version", null)
                .set("last_successful_stage", "PARSED"));
    }

    @Transactional
    public void markTranscribed(Document document, String key, String metadata, String parserVersion) {
        writeOwned(document, update -> update.set("pipeline_status", "TRANSCRIBED")
                .set("asr_result_key", key).set("asr_metadata_json", metadata)
                .set("parser_version", parserVersion).set("parsed_text_key", null).set("parsed_text_hash", null)
                .set("last_successful_stage", "TRANSCRIBED"));
    }

    @Transactional
    public void replaceChunks(Document document, List<DocumentChunk> chunks, String chunkerVersion) {
        // Lock and fence the document before changing children, so a lost lease changes nothing.
        writeOwned(document, update -> update.set("pipeline_status", "CHUNKED")
                .set("chunker_version", chunkerVersion).set("index_target", null)
                .set("last_successful_stage", "CHUNKED"));
        // An empty range DELETE takes a gap lock: concurrent new documents can block each other's inserts.
        // The document row already serializes its writers; read existing IDs without locking the range.
        List<DocumentChunk> previous = documentChunkMapper.selectList(Wrappers.<DocumentChunk>query()
                .select("id").eq("document_id", document.getId()));
        for (DocumentChunk old : previous) { documentChunkMapper.deleteById(old.getId()); }
        for (DocumentChunk chunk : chunks) { documentChunkMapper.insert(chunk); }
    }

    @Transactional
    public void markChunkEmbedded(Document document, DocumentChunk chunk) {
        writeOwned(document, update -> { });
        documentChunkMapper.update(null, Wrappers.<DocumentChunk>update()
                .eq("document_id", document.getId()).eq("chunk_id", chunk.getChunkId())
                .set("embedding_status", "COMPLETED"));
        chunk.setEmbeddingStatus("COMPLETED");
    }

    @Transactional
    public void markEmbeddingCompleted(Document document) {
        writeOwned(document, update -> update.set("pipeline_status", "EMBEDDED")
                .set("last_successful_stage", "EMBEDDED"));
    }

    @Transactional
    public void beginIndexing(Document document, List<DocumentChunk> chunks, String target) {
        writeOwned(document, update -> update.set("pipeline_status", "INDEXING").set("index_target", target));
        if (!target.equals(document.getIndexTarget())) {
            documentChunkMapper.update(null, Wrappers.<DocumentChunk>update()
                    .eq("document_id", document.getId()).set("indexed_at", null));
            chunks.forEach(chunk -> chunk.setIndexedAt(null));
        }
    }

    @Transactional
    public void markIndexed(Document document, List<String> chunkIds) {
        writeOwned(document, update -> { });
        if (!chunkIds.isEmpty()) {
            documentChunkMapper.update(null, Wrappers.<DocumentChunk>update()
                    .eq("document_id", document.getId()).in("chunk_id", chunkIds)
                    .set("indexed_at", LocalDateTime.now()));
        }
    }

    @Transactional
    public void markCompleted(Document document) {
        writeOwned(document, update -> update.set("pipeline_status", "INDEXED")
                .set("last_successful_stage", "INDEXED").set("error_message", null)
                .set("processing_token", null).set("lease_until", null));
        Long pending = documentChunkMapper.selectCount(Wrappers.<DocumentChunk>query()
                .eq("document_id", document.getId()).isNull("indexed_at"));
        if (pending != 0) { throw new BusinessException("尚有未确认索引成功的 chunk: " + pending); }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Document document, PipelineStatus stage, Throwable failure) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        documentMapper.update(null, Wrappers.<Document>update().eq("id", document.getId())
                .eq("processing_token", document.getProcessingToken())
                .set("pipeline_status", "FAILED").set("error_message", stage.name() + ": " + detail)
                .set("processing_token", null).set("lease_until", null).set("updated_at", LocalDateTime.now()));
    }

    private void writeOwned(Document document, java.util.function.Consumer<UpdateWrapper<Document>> changes) {
        LocalDateTime now = LocalDateTime.now();
        var update = Wrappers.<Document>update().eq("id", document.getId())
                .eq("processing_token", document.getProcessingToken()).gt("lease_until", now)
                .set("lease_until", now.plus(properties.leaseDuration())).set("updated_at", now);
        changes.accept(update);
        if (documentMapper.update(null, update) != 1) {
            throw new BusinessException("文档执行权已过期或被接管: documentId=" + document.getId());
        }
    }
}
