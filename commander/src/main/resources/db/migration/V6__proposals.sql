CREATE TABLE action_proposals (
                                  id BIGSERIAL PRIMARY KEY,
                                  incident_id BIGINT NOT NULL REFERENCES incidents(id),
                                  action_type VARCHAR(40) NOT NULL,
                                  target_service VARCHAR(100) NOT NULL,
                                  rationale TEXT,
                                  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  decided_by VARCHAR(100),
                                  decided_at TIMESTAMPTZ,
                                  executed_at TIMESTAMPTZ,
                                  result TEXT,
                                  UNIQUE (incident_id, action_type, target_service)
);

CREATE INDEX idx_proposals_status ON action_proposals (status);