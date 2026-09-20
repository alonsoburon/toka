-- 003_reminders.down.sql

DROP INDEX IF EXISTS idx_instances_generated_from;
ALTER TABLE task_instances DROP COLUMN generated_from_instance_id;
ALTER TABLE task_templates DROP COLUMN reminder_times;
