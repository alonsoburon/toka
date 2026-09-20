-- 002_sync.down.sql

DROP INDEX IF EXISTS idx_mutations_household;
DROP TABLE IF EXISTS sync_mutations;

DROP INDEX IF EXISTS idx_tombstones_sync;
DROP TABLE IF EXISTS sync_tombstones;

DROP INDEX IF EXISTS idx_templates_client_id;
DROP INDEX IF EXISTS idx_instances_client_id;
DROP INDEX IF EXISTS idx_people_client_id;

DROP INDEX IF EXISTS idx_people_sync;
DROP INDEX IF EXISTS idx_templates_sync;
DROP INDEX IF EXISTS idx_instances_sync;

ALTER TABLE people         DROP COLUMN client_id;
ALTER TABLE task_templates DROP COLUMN client_id;
ALTER TABLE task_instances DROP COLUMN client_id;

ALTER TABLE people         DROP COLUMN row_version;
ALTER TABLE task_templates DROP COLUMN row_version;
ALTER TABLE task_instances DROP COLUMN row_version;

DROP TABLE IF EXISTS sync_counter;
