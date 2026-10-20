CREATE OR REPLACE FUNCTION guard_incident_close() RETURNS trigger AS $$
BEGIN
  IF ((OLD.resolved_at IS NULL AND NEW.resolved_at IS NOT NULL)
      OR (NEW.status = 'RESOLVED' AND OLD.status <> 'RESOLVED'))
     AND EXISTS (SELECT 1 FROM action_proposals p
                 WHERE p.incident_id = NEW.id AND p.status = 'EXECUTING') THEN
    NEW.resolved_at := OLD.resolved_at;
    NEW.status := OLD.status;
    RAISE NOTICE 'incident % was not closed: a remediation action is executing', NEW.id;
RETURN NEW;
END IF;

  -- A failed fix must not be relabelled as a success when its alerts clear.
  IF OLD.status = 'ESCALATED' AND NEW.status = 'RESOLVED' THEN
    NEW.status := 'ESCALATED';
END IF;

RETURN NEW;
END;
$$ LANGUAGE plpgsql;