CREATE TABLE alerts (
                        id BIGSERIAL PRIMARY KEY,
                        fingerprint VARCHAR(64) NOT NULL UNIQUE,
                        alertname VARCHAR(100) NOT NULL,
                        application VARCHAR(100),
                        severity VARCHAR(30),
                        summary TEXT,
                        status VARCHAR(20) NOT NULL,
                        starts_at TIMESTAMPTZ,
                        received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);