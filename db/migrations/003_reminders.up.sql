-- 003_reminders.up.sql
--
-- Dos añadidos independientes:
--
--   1. reminder_times — las horas a las que avisar de una tarea, por plantilla. Se
--      guarda como texto "HH:MM,HH:MM" y se interpreta como hora local del teléfono
--      (cada uno avisa a su propia hora de pared). El servidor no la mira.
--
--   2. generated_from_instance_id — enlaza la instancia que se generó al completar
--      otra. Sin este enlace, deshacer un completado no sabría cuál borrar: la
--      recurrencia no dejaba rastro de su origen. Es la pieza que hace posible el
--      botón "deshacer".

ALTER TABLE task_templates ADD COLUMN reminder_times TEXT;

ALTER TABLE task_instances ADD COLUMN generated_from_instance_id INTEGER
    REFERENCES task_instances(id);

CREATE INDEX idx_instances_generated_from
    ON task_instances(generated_from_instance_id)
    WHERE generated_from_instance_id IS NOT NULL;
