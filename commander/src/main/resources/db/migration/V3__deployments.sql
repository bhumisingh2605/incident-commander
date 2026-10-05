CREATE TABLE deployments (
                             id BIGSERIAL PRIMARY KEY,
                             service VARCHAR(100) NOT NULL,
                             version VARCHAR(50) NOT NULL,
                             deployed_by VARCHAR(100) NOT NULL DEFAULT 'unknown',
                             notes TEXT,
                             deployed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deployments_time ON deployments (deployed_at DESC);
