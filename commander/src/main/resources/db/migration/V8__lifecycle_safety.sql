ALTER TABLE action_proposals ADD COLUMN claimed_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION guard_incident_close() RETURNS trigger AS $$
BEGIN
  IF ((OLD.resolved_at IS NULL AND NEW.resolved_at IS NOT NULL)
      OR (NEW.status = 'RESOLVED' AND OLD.status <> 'RESOLVED'))
     AND EXISTS (SELECT 1 FROM action_proposals p
                 WHERE p.incident_id = NEW.id AND p.status = 'EXECUTING') THEN
    NEW.resolved_at := OLD.resolved_at;
    NEW.status := OLD.status;
    RAISE NOTICE 'incident % was not closed: a remediation action is executing', NEW.id;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_incident_close
    BEFORE UPDATE ON incidents
    FOR EACH ROW EXECUTE FUNCTION guard_incident_close();