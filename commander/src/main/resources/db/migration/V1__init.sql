CREATE TABLE incidents (
                           id BIGSERIAL PRIMARY KEY,
                           title TEXT NOT NULL,
                           status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
                           created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);