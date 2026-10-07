ALTER TABLE incidents
    ADD COLUMN verification TEXT,
  ADD COLUMN verified_at TIMESTAMPTZ;