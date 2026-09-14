ALTER TABLE learning_sessions ADD COLUMN plan_run_id BIGINT NULL,
    ADD INDEX idx_session_outline (user_id, plan_run_id);
ALTER TABLE knowledge_points ADD COLUMN outline_node_id BIGINT NULL,
    ADD INDEX idx_point_outline (outline_node_id, status);
