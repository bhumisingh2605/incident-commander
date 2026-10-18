CREATE TABLE audit_events (
                              id BIGSERIAL PRIMARY KEY,
                              at TIMESTAMPTZ NOT NULL DEFAULT now(),
                              actor VARCHAR(100) NOT NULL,
                              action VARCHAR(40) NOT NULL,
                              subject_type VARCHAR(20) NOT NULL,
                              subject_id BIGINT NOT NULL,
                              from_status VARCHAR(20),
                              to_status VARCHAR(20),
                              detail TEXT
);

CREATE INDEX idx_audit_subject ON audit_events (subject_type, subject_id, id DESC);

CREATE OR REPLACE FUNCTION audit_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION audit_immutable();

CREATE OR REPLACE FUNCTION audit_proposal_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_events (actor, action, subject_type, subject_id, to_status, detail)
    VALUES ('system', 'STATUS_CHANGE', 'PROPOSAL', NEW.id, NEW.status,
            NEW.action_type || ' ' || NEW.target_service || ' on incident ' || NEW.incident_id);
  ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
    INSERT INTO audit_events (actor, action, subject_type, subject_id, from_status, to_status, detail)
    VALUES (CASE WHEN NEW.status IN ('APPROVED', 'REJECTED') THEN COALESCE(NEW.decided_by, 'system') ELSE 'system' END,
            'STATUS_CHANGE', 'PROPOSAL', NEW.id, OLD.status, NEW.status,
            left(COALESCE(NEW.result, NEW.action_type || ' ' || NEW.target_service), 300));
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_proposal
    AFTER INSERT OR UPDATE ON action_proposals
                        FOR EACH ROW EXECUTE FUNCTION audit_proposal_change();

CREATE OR REPLACE FUNCTION audit_incident_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_events (actor, action, subject_type, subject_id, to_status, detail)
    VALUES ('system', 'STATUS_CHANGE', 'INCIDENT', NEW.id, NEW.status, left(NEW.title, 200));
  ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
    INSERT INTO audit_events (actor, action, subject_type, subject_id, from_status, to_status, detail)
    VALUES ('system', 'STATUS_CHANGE', 'INCIDENT', NEW.id, OLD.status, NEW.status, left(NEW.title, 200));
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_incident
    AFTER INSERT OR UPDATE ON incidents
                        FOR EACH ROW EXECUTE FUNCTION audit_incident_change();