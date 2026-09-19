CREATE TABLE learning_preferences (
    user_id BIGINT NOT NULL PRIMARY KEY,
    explanation_style VARCHAR(300) NOT NULL DEFAULT '',
    learning_goal VARCHAR(500) NOT NULL DEFAULT '',
    memory_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME NOT NULL
);

CREATE TABLE learning_memories (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    quiz_id BIGINT NOT NULL,
    topic VARCHAR(512) NOT NULL,
    correct_count INT NOT NULL,
    question_count INT NOT NULL,
    mistakes_json JSON NOT NULL,
    included BOOLEAN NOT NULL DEFAULT TRUE,
    recorded_at DATETIME NOT NULL,
    UNIQUE KEY uk_memory_quiz (user_id, quiz_id),
    KEY idx_memory_scope (user_id, knowledge_base_id, recorded_at DESC, id DESC)
);
