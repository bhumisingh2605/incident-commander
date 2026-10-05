CREATE TABLE tool_calls (
                            id BIGSERIAL PRIMARY KEY,
                            run_id VARCHAR(64),
                            tool_name VARCHAR(100) NOT NULL,
                            args TEXT,
                            result_summary TEXT,
                            status VARCHAR(20) NOT NULL,
                            duration_ms BIGINT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tool_calls_time ON tool_calls (created_at DESC);