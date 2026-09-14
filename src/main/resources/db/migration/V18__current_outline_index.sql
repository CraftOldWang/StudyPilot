-- Read the latest course outline without sorting rows containing large source JSON.
CREATE INDEX idx_plan_run_current
    ON learning_plan_runs (user_id, knowledge_base_id, created_at DESC, id DESC);
