-- 003_sync.down.sql

DROP TABLE IF EXISTS sync_mutations;
DROP TABLE IF EXISTS sync_tombstones;

DROP INDEX IF EXISTS idx_people_client_id;
DROP INDEX IF EXISTS idx_instances_client_id;
DROP INDEX IF EXISTS idx_templates_client_id;
ALTER TABLE people         DROP COLUMN IF EXISTS client_id;
ALTER TABLE task_instances DROP COLUMN IF EXISTS client_id;
ALTER TABLE task_templates DROP COLUMN IF EXISTS client_id;

DROP INDEX IF EXISTS idx_instances_sync;
DROP INDEX IF EXISTS idx_templates_sync;
DROP INDEX IF EXISTS idx_people_sync;

DROP TRIGGER IF EXISTS trg_task_instances_row_version ON task_instances;
DROP TRIGGER IF EXISTS trg_task_templates_row_version ON task_templates;
DROP TRIGGER IF EXISTS trg_people_row_version ON people;

CREATE TRIGGER trg_people_updated_at
    BEFORE UPDATE ON people
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_task_templates_updated_at
    BEFORE UPDATE ON task_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_task_instances_updated_at
    BEFORE UPDATE ON task_instances
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP FUNCTION IF EXISTS bump_row_version();

ALTER TABLE task_instances DROP COLUMN IF EXISTS row_version;
ALTER TABLE task_templates DROP COLUMN IF EXISTS row_version;
ALTER TABLE people         DROP COLUMN IF EXISTS row_version;

DROP FUNCTION IF EXISTS next_row_version();
DROP TABLE IF EXISTS sync_counter;
