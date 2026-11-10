package com.studyagent.ingest.upload;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.mapper.LogicalDocumentMapper;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
import org.junit.jupiter.api.Test;

class UploadPublicationServiceTest {
    @Test
    void existingFileAndDocumentDoNotQueueAnotherPipeline() {
        FileRecordMapper files = mock(FileRecordMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        UploadSessionMapper sessions = mock(UploadSessionMapper.class);
        DocumentIndexProducer producer = mock(DocumentIndexProducer.class);
        FileRecord file = new FileRecord();
        file.setId(3L);
        Document document = new Document();
        document.setId(4L);
        when(files.selectOne(any())).thenReturn(file);
        when(documents.selectOne(any())).thenReturn(document);
        var result = new UploadPublicationService(files, documents, sessions, producer, mock(LogicalDocumentMapper.class))
                .publish(1L, 2L, "hash", "key", "test.txt", 5L, "text/plain", 10L, null);
        assertThat(result.documentId()).isEqualTo(4L);
        assertThat(result.status()).isEqualTo("DUPLICATED");
        verify(files, never()).insert(any(FileRecord.class));
        verify(documents, never()).insert(any(Document.class));
        verifyNoInteractions(producer);
    }
}
