ALTER TABLE incidents
    ADD COLUMN service VARCHAR(100),
  ADD COLUMN severity VARCHAR(30),
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN resolved_at TIMESTAMPTZ,
  ADD COLUMN rc_summary TEXT,
  ADD COLUMN rc_service VARCHAR(100),
  ADD COLUMN rc_category VARCHAR(40),
  ADD COLUMN rc_confidence DOUBLE PRECISION,
  ADD COLUMN rc_evidence TEXT,
  ADD COLUMN rc_actions TEXT;

ALTER TABLE alerts ADD COLUMN incident_id BIGINT REFERENCES incidents(id);

CREATE TABLE agent_runs (
                            run_id VARCHAR(64) PRIMARY KEY,
                            incident_id BIGINT REFERENCES incidents(id),
                            type VARCHAR(30) NOT NULL DEFAULT 'ANALYST',
                            model VARCHAR(100),
                            started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            finished_at TIMESTAMPTZ,
                            outcome VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
                            tool_calls INT,
                            seconds BIGINT,
                            findings TEXT,
                            error TEXT
);

CREATE INDEX idx_incidents_status ON incidents (status);