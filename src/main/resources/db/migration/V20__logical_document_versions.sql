CREATE TABLE logical_documents (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    active_document_id BIGINT,
    INDEX idx_logical_document_scope (user_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO logical_documents (id, user_id, knowledge_base_id, active_document_id)
SELECT id, user_id, knowledge_base_id, CASE WHEN pipeline_status = 'INDEXED' THEN id END
FROM documents;

ALTER TABLE documents
    ADD COLUMN logical_document_id BIGINT,
    ADD COLUMN previous_document_id BIGINT,
    ADD COLUMN original_file_record_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN previous_document_id IS NULL THEN file_record_id END
    ) STORED;

UPDATE documents SET logical_document_id = id;

ALTER TABLE documents
    MODIFY COLUMN logical_document_id BIGINT NOT NULL,
    DROP INDEX uk_document_scope_file,
    ADD UNIQUE KEY uk_original_document_file (user_id, knowledge_base_id, original_file_record_id),
    ADD UNIQUE KEY uk_document_version_file (logical_document_id, previous_document_id, file_record_id);

ALTER TABLE upload_sessions ADD COLUMN logical_document_id BIGINT;
