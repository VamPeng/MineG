-- +goose Up
-- +goose StatementBegin
CREATE OR REPLACE FUNCTION mineg.enforce_user_status_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'approved user status is immutable';
    END IF;
    IF NEW.status = 'APPROVED' AND NEW.reviewed_at IS NULL THEN
        RAISE EXCEPTION 'user cannot be approved before review';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

UPDATE mineg.users
SET status = 'APPROVED', updated_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING' AND reviewed_at IS NOT NULL;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
CREATE OR REPLACE FUNCTION mineg.enforce_user_status_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

UPDATE mineg.users target
SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
WHERE target.status = 'APPROVED'
  AND (
      NOT EXISTS (
          SELECT 1 FROM mineg.key_grant_tasks task
          WHERE task.user_id = target.id AND task.state = 'READY'
      ) OR NOT EXISTS (
          SELECT 1 FROM mineg.family_key_envelopes envelope
          WHERE envelope.user_id = target.id
      )
  );

CREATE OR REPLACE FUNCTION mineg.enforce_user_status_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'approved user status is immutable';
    END IF;
    IF NEW.status = 'APPROVED' AND (
        NOT EXISTS (
            SELECT 1 FROM mineg.key_grant_tasks task
            WHERE task.user_id = NEW.id AND task.state = 'READY'
        ) OR NOT EXISTS (
            SELECT 1 FROM mineg.family_key_envelopes envelope
            WHERE envelope.user_id = NEW.id
        )
    ) THEN
        RAISE EXCEPTION 'user cannot be approved before the family key envelope is ready';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
-- +goose StatementEnd
