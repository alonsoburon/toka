-- 001_init.up.sql

CREATE TYPE task_status AS ENUM ('pending', 'done', 'skipped');

CREATE TABLE households (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT NOT NULL,
    invite_code  TEXT UNIQUE NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT NOT NULL,
    updated_by   BIGINT NOT NULL
);

CREATE TABLE people (
    id           BIGSERIAL PRIMARY KEY,
    household_id BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    color        TEXT NOT NULL DEFAULT '#a78bfa',
    avatar_emoji TEXT NOT NULL DEFAULT '🐣',
    token        TEXT UNIQUE NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT NOT NULL,
    updated_by   BIGINT NOT NULL
);

CREATE TABLE task_templates (
    id                    BIGSERIAL PRIMARY KEY,
    household_id          BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    description           TEXT,
    recurrence_days       INT,
    preferred_assignee_id BIGINT REFERENCES people(id),
    is_active             BOOLEAN NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            BIGINT NOT NULL,
    updated_by            BIGINT NOT NULL
);

CREATE TABLE task_instances (
    id              BIGSERIAL PRIMARY KEY,
    template_id     BIGINT NOT NULL REFERENCES task_templates(id) ON DELETE CASCADE,
    household_id    BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    status          task_status NOT NULL DEFAULT 'pending',
    due_at          TIMESTAMPTZ NOT NULL,
    assigned_to_id  BIGINT REFERENCES people(id),
    completed_by_id BIGINT REFERENCES people(id),
    completed_at    TIMESTAMPTZ,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT NOT NULL,
    updated_by      BIGINT NOT NULL
);

-- Function + triggers: auto-set updated_at on every UPDATE for every table
CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_households_updated_at
    BEFORE UPDATE ON households
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_people_updated_at
    BEFORE UPDATE ON people
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_task_templates_updated_at
    BEFORE UPDATE ON task_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_task_instances_updated_at
    BEFORE UPDATE ON task_instances
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Indexes
CREATE INDEX idx_people_household ON people(household_id);
CREATE INDEX idx_people_token ON people(token);
CREATE INDEX idx_templates_household ON task_templates(household_id) WHERE is_active = true;
CREATE INDEX idx_instances_household_status ON task_instances(household_id, status);
CREATE INDEX idx_instances_template ON task_instances(template_id);
CREATE INDEX idx_instances_due_at ON task_instances(household_id, due_at) WHERE status = 'pending';

-- FK for households.created_by (needs deferrable because people don't exist yet)
ALTER TABLE households ADD CONSTRAINT fk_households_created_by
    FOREIGN KEY (created_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE households ADD CONSTRAINT fk_households_updated_by
    FOREIGN KEY (updated_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;

-- FK for people.created_by (self-referential: first person must reference itself)
ALTER TABLE people ADD CONSTRAINT fk_people_created_by
    FOREIGN KEY (created_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE people ADD CONSTRAINT fk_people_updated_by
    FOREIGN KEY (updated_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;

-- FK for task_templates.created_by/updated_by (created by an existing person)
ALTER TABLE task_templates ADD CONSTRAINT fk_templates_created_by
    FOREIGN KEY (created_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE task_templates ADD CONSTRAINT fk_templates_updated_by
    FOREIGN KEY (updated_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;

-- FK for task_instances.created_by/updated_by (created by an existing person)
ALTER TABLE task_instances ADD CONSTRAINT fk_instances_created_by
    FOREIGN KEY (created_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE task_instances ADD CONSTRAINT fk_instances_updated_by
    FOREIGN KEY (updated_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;
