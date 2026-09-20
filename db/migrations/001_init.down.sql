-- 001_init.down.sql

DROP INDEX IF EXISTS idx_instances_due_at;
DROP INDEX IF EXISTS idx_instances_template;
DROP INDEX IF EXISTS idx_instances_household_status;
DROP INDEX IF EXISTS idx_templates_household;
DROP INDEX IF EXISTS idx_people_household;

DROP TABLE IF EXISTS task_instances;
DROP TABLE IF EXISTS task_templates;
DROP TABLE IF EXISTS people;
DROP TABLE IF EXISTS households;
