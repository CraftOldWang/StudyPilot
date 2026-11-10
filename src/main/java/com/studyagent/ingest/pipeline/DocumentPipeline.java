package com.studyagent.ingest.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.algo.chunk.ChunkSegment;
import com.studyagent.algo.chunk.SourceLocation;
import com.studyagent.algo.chunk.StructuredChunker;
import com.studyagent.algo.chunk.TokenWindowChunker;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.config.AiModelProperties;
import com.studyagent.config.RagProperties;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.ingest.parse.DocumentTextParser;
import com.studyagent.ingest.parse.MediaTranscriptionService;
import com.studyagent.ingest.parse.MineruDocumentParser;
import com.studyagent.ingest.parse.ParsedDocument;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.FileRecord;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import com.studyagent.rag.index.ElasticsearchIndexer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentPipeline {

    public static final String PARSER_VERSION = "tika-3.3.0";
    public static final String CHUNKER_VERSION = "v2-jtokkit1.1.0-cl100k";

    private final DocumentPipelinePersistence persistence;
    private final ObjectStorageService objectStorageService;
    private final DocumentTextParser documentTextParser;
    private final StructuredChunker structuredChunker;
    private final TokenWindowChunker tokenWindowChunker;
    private final DocumentEmbeddingArtifacts embeddingArtifacts;
    private final ElasticsearchIndexer elasticsearchIndexer;
    private final AiModelProperties aiModelProperties;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final ElasticsearchProperties elasticsearchProperties;
    private final MediaTranscriptionService mediaTranscription;
    private final MineruDocumentParser mineruParser;

    public boolean process(Long documentId) {
        return execute(documentId, true);
    }

    public boolean processPending(Long documentId) {
        return execute(documentId, false);
    }

    private boolean execute(Long documentId, boolean allowFailedRetry) {
        ModelCallScope previous = ModelCallScope.current();
        if (previous == null) {
            ModelCallScope.bind(new ModelCallScope(UUID.randomUUID().toString(), "INGEST /documents/" + documentId));
        }
        try {
            return executeScoped(documentId, allowFailedRetry);
        } finally {
            if (previous == null) { ModelCallScope.clear(); }
        }
    }

    private boolean executeScoped(Long documentId, boolean allowFailedRetry) {
        Document document = persistence.claim(documentId, allowFailedRetry);
        if (document == null) {
            return false;
        }

        PipelineStatus stage = PipelineStatus.PARSING;
        try {
            boolean media = MediaTranscriptionService.isMedia(document.getTitle());
            String parserVersion = PARSER_VERSION;
            if (media) {
                parserVersion = mediaTranscription.processorVersion();
            } else if (mineruParser.supports(document.getTitle())) {
                parserVersion = MineruDocumentParser.VERSION;
            }
            boolean reusableParse = parserVersion.equals(document.getParserVersion())
                    && document.getParsedTextKey() != null;
            ParsedDocument parsed;
            if (reusableParse) {
                parsed = loadParsed(document, parserVersion);
            } else if (media) {
                stage = PipelineStatus.TRANSCRIBING;
                persistence.startStage(document, stage);
                parsed = new ParsedDocument(mediaTranscription.transcribe(document));
            } else {
                parsed = parse(document, parserVersion);
            }
            if (!reusableParse) {
                saveParsed(document, parsed, parserVersion);
            }

            stage = PipelineStatus.CHUNKING;
            persistence.startStage(document, stage);
            boolean reusableChunks = reusableParse && chunkerVersion().equals(document.getChunkerVersion());
            List<DocumentChunk> chunks = reusableChunks ? persistence.loadChunks(documentId) : buildChunks(document, parsed);
            if (chunks.isEmpty()) {
                throw new BusinessException("文档分块结果为空");
            }
            if (!reusableChunks) {
                persistence.replaceChunks(document, chunks, chunkerVersion());
                document.setIndexTarget(null);
            } else {
                log.info("复用持久化 chunks: documentId={}, count={}", documentId, chunks.size());
            }

            stage = PipelineStatus.EMBEDDING;
            persistence.startStage(document, stage);
            List<EmbeddedChunk> embeddedChunks = embed(document, chunks);
            persistence.markEmbeddingCompleted(document);

            stage = PipelineStatus.INDEXING;
            String target = elasticsearchProperties.physicalIndex() + ":" + aiModelProperties.embedding().model()
                    + ":" + aiModelProperties.embedding().dimensions();
            persistence.beginIndexing(document, chunks, target);
            var pending = embeddedChunks.stream().filter(chunk -> chunk.chunk().getIndexedAt() == null).toList();
            if (!pending.isEmpty()) {
                persistence.renewLease(document);
                var result = elasticsearchIndexer.bulkIndexAcknowledged(toIndexDocuments(document, pending));
                persistence.markIndexed(document, result.succeededIds());
                if (!result.failures().isEmpty()) {
                    throw new BusinessException("部分 chunk 索引失败: " + result.failures());
                }
            }
            persistence.markCompleted(document);
            return true;
        } catch (RuntimeException ex) {
            persistence.markFailed(document, stage, ex);
            throw ex;
        }
    }

    private void saveParsed(Document document, ParsedDocument parsed, String version) {
        String text = parsed.text();
        if (text.isBlank()) { throw new BusinessException("文档解析结果为空"); }
        String hash = sha256(text);
        String base = "parsed/" + document.getUserId() + "/" + document.getId() + "/" + version + "/" + hash;
        String key;
        if (MineruDocumentParser.VERSION.equals(version)) {
            String prefix = base + "/";
            key = prefix + MineruDocumentParser.MARKDOWN;
            for (var image : parsed.images().entrySet()) {
                putParsedObject(prefix + image.getKey(), image.getValue(), "image/jpeg");
            }
            try {
                putParsedObject(prefix + MineruDocumentParser.SOURCES, objectMapper.writeValueAsBytes(parsed.blocks()),
                        "application/json");
            } catch (JsonProcessingException ex) {
                throw new BusinessException("序列化 MinerU 来源坐标失败: " + ex.getMessage());
            }
        } else {
            key = base + ".txt";
        }
        String contentType = key.endsWith(".md") ? "text/markdown" : "text/plain";
        putParsedObject(key, text.getBytes(StandardCharsets.UTF_8), contentType + "; charset=utf-8");
        persistence.markParsed(document, key, hash, version);
    }

    private void putParsedObject(String key, byte[] bytes, String contentType) {
        objectStorageService.putObject(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    private ParsedDocument loadParsed(Document document, String version) {
        try (InputStream stream = objectStorageService.getObject(document.getParsedTextKey())) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (!sha256(text).equals(document.getParsedTextHash())) {
                throw new BusinessException("规范文本产物哈希不匹配: documentId=" + document.getId());
            }
            log.info("复用规范文本: documentId={}", document.getId());
            if (MineruDocumentParser.VERSION.equals(version)) {
                String key = document.getParsedTextKey();
                String sourcesKey = key.substring(0, key.lastIndexOf('/') + 1) + MineruDocumentParser.SOURCES;
                try (InputStream sources = objectStorageService.getObject(sourcesKey)) {
                    List<ParsedDocument.Block> blocks = objectMapper.readValue(sources, new TypeReference<>() {});
                    return new ParsedDocument(text, blocks, Map.of());
                }
            }
            return new ParsedDocument(text);
        } catch (IOException ex) {
            throw new BusinessException("读取解析产物失败: " + ex.getMessage());
        }
    }

    private ParsedDocument parse(Document document, String version) {
        FileRecord file = persistence.loadFile(document.getFileRecordId());
        if (file == null) {
            throw new BusinessException("文件记录不存在: " + document.getFileRecordId());
        }
        try (InputStream inputStream = objectStorageService.getObject(file.getStorageKey())) {
            if (MineruDocumentParser.VERSION.equals(version)) {
                return mineruParser.parse(inputStream, document.getTitle(), () -> persistence.renewLease(document));
            }
            return new ParsedDocument(documentTextParser.parse(inputStream));
        } catch (IOException ex) {
            throw new BusinessException("读取文档对象失败: " + ex.getMessage());
        }
    }

    private List<DocumentChunk> buildChunks(Document document, ParsedDocument parsed) {
        String parsedText = parsed.text();
        List<DocumentChunk> chunks = new ArrayList<>();
        List<ChunkSegment> parents = ragProperties.chunkStrategy() == RagProperties.ChunkStrategy.STRUCTURED
                ? structuredChunker.parentChunks(parsedText, ragProperties.parentChunkSize())
                : tokenWindowChunker.split(parsedText, ragProperties.parentChunkSize(), ragProperties.parentChunkOverlap());
        int childIndex = 0;
        LocalDateTime createdAt = LocalDateTime.now();
        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            ChunkSegment parentSegment = parents.get(parentIndex);
            DocumentChunk parent = chunk(document.getId(), "PARENT", parentIndex, parentSegment, null, createdAt, parsed.blocks());
            chunks.add(parent);
            List<ChunkSegment> children = ragProperties.chunkStrategy() == RagProperties.ChunkStrategy.STRUCTURED
                    ? tokenWindowChunker.splitStructured(parentSegment, ragProperties.chunkSize(), ragProperties.chunkOverlap())
                    : tokenWindowChunker.split(parentSegment, ragProperties.chunkSize(), ragProperties.chunkOverlap());
            for (ChunkSegment childSegment : children) {
                chunks.add(chunk(
                        document.getId(),
                        "CHILD",
                        childIndex++,
                        childSegment,
                        parent.getChunkId(),
                        createdAt,
                        parsed.blocks()));
            }
        }
        return List.copyOf(chunks);
    }

    private DocumentChunk chunk(
            Long documentId,
            String chunkType,
            int chunkIndex,
            ChunkSegment segment,
            String parentChunkId,
            LocalDateTime createdAt,
            List<ParsedDocument.Block> sourceBlocks
    ) {
        String contentHash = sha256(segment.content());
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkId(chunkId(documentId, chunkType, chunkIndex, contentHash));
        chunk.setParentChunkId(parentChunkId);
        chunk.setChunkType(chunkType);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(segment.content());
        chunk.setContentHash(contentHash);
        chunk.setSourceLocation(sourceLocationJson(segment.sourceLocation(), sourceBlocks));
        chunk.setEmbeddingStatus("PARENT".equals(chunkType) ? "NOT_REQUIRED" : "PENDING");
        chunk.setCreatedAt(createdAt);
        return chunk;
    }

    private List<EmbeddedChunk> embed(Document document, List<DocumentChunk> chunks) {
        List<EmbeddedChunk> result = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            float[] vector = null;
            if ("CHILD".equals(chunk.getChunkType())) {
                persistence.renewLease(document);
                vector = embeddingArtifacts.loadOrCreate(document.getUserId(), chunk);
                persistence.markChunkEmbedded(document, chunk);
            }
            result.add(new EmbeddedChunk(chunk, vector));
        }
        return List.copyOf(result);
    }

    private List<ElasticsearchChunkDocument> toIndexDocuments(
            Document document,
            List<EmbeddedChunk> embeddedChunks
    ) {
        String embeddingModel = aiModelProperties.embedding().model();
        return embeddedChunks.stream().map(embedded -> {
            DocumentChunk chunk = embedded.chunk();
            return new ElasticsearchChunkDocument(
                    document.getUserId() == null ? null : String.valueOf(document.getUserId()),
                    String.valueOf(document.getKnowledgeBaseId()),
                    String.valueOf(document.getId()),
                    chunk.getChunkId(),
                    chunk.getParentChunkId(),
                    chunk.getChunkType(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    chunk.getContentHash(),
                    document.getTitle(),
                    chunk.getSourceLocation(),
                    embedded.embedding(),
                    chunkerVersion(),
                    embeddingModel,
                    chunk.getCreatedAt());
        }).toList();
    }

    String chunkId(Long documentId, String chunkType, int chunkIndex, String contentHash) {
        return sha256(documentId + chunkerVersion() + chunkType + chunkIndex + contentHash);
    }

    public String chunkerVersion() {
        return CHUNKER_VERSION + "-" + ragProperties.chunkStrategy().name().toLowerCase(java.util.Locale.ROOT)
                + "-c" + ragProperties.chunkSize() + "o" + ragProperties.chunkOverlap()
                + "-p" + ragProperties.parentChunkSize() + "o" + ragProperties.parentChunkOverlap();
    }

    private String sourceLocationJson(SourceLocation location, List<ParsedDocument.Block> sourceBlocks) {
        List<ParsedDocument.Block> overlapping = sourceBlocks.stream()
                .filter(block -> block.overlaps(location.startInclusive(), location.endExclusive())).toList();
        try {
            return objectMapper.writeValueAsString(new StoredSourceLocation(
                    location.startInclusive(),
                    location.endExclusive(),
                    location.headingPath(),
                    overlapping));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("序列化 chunk 来源坐标失败: " + ex.getMessage());
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private record EmbeddedChunk(DocumentChunk chunk, float[] embedding) {
    }

    private record StoredSourceLocation(int startOffset, int endOffset, List<String> headingPath,
                                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ParsedDocument.Block> blocks) {
    }
}
