package com.studyagent.ingest.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.model.FileRecord;
import com.studyagent.rag.web.KnowledgeBaseService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {
    @Mock UploadPublicationService publication;
    @Mock NativeMultipartUploadService multipart;
    @Mock ObjectStorageService storage;
    @Mock RedissonClient redis;
    @Mock KnowledgeBaseService knowledgeBases;
    @Mock RLock lock;
    private FileUploadService service;

    @BeforeEach
    void setup() {
        service = new FileUploadService(publication, multipart, storage, redis, knowledgeBases);
    }

    @ParameterizedTest
    @CsvSource({"demo.pdf,application/pdf", "course.pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "notes.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "notes.txt,text/plain", "notes.md,text/markdown", "notes.markdown,text/plain", "course.PPTX,application/octet-stream"})
    void duplicateReturnsExistingDocumentWithoutAnotherObject(String filename, String type) {
        String hash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        FileRecord existing = new FileRecord();
        existing.setId(22L);
        existing.setStorageKey("old-key");
        when(publication.find(1L, 1L, hash)).thenReturn(existing);
        when(redis.getLock(UploadFileSupport.dedupLock(1L, 1L, hash))).thenReturn(lock);
        when(publication.publish(eq(1L), eq(1L), eq(hash), eq("old-key"), eq(filename), eq(5L), eq(type), isNull(), isNull()))
                .thenReturn(new UploadResultResponse(22L, 33L, "DUPLICATED"));
        var result = service.uploadSingle(1L, 1L,
                new MockMultipartFile("file", filename, type, "hello".getBytes(StandardCharsets.UTF_8)), null);
        assertThat(result.documentId()).isEqualTo(33L);
        verifyNoInteractions(storage);
        var order = inOrder(publication, lock);
        order.verify(lock).lock();
        order.verify(publication).find(1L, 1L, hash);
        order.verify(publication).publish(eq(1L), eq(1L), eq(hash), eq("old-key"), eq(filename), eq(5L), eq(type),
                isNull(), isNull());
        order.verify(lock).unlock();
    }

    @ParameterizedTest
    @CsvSource({"notes.docx,text/plain", "notes.zip,application/zip", "notes.pdf,text/plain"})
    void unsupportedTypeIsRejectedBeforeStorage(String filename, String type) {
        assertThatThrownBy(() -> service.uploadSingle(1L, 1L,
                new MockMultipartFile("file", filename, type, new byte[]{1}), null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage, publication);
    }
}
