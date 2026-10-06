-- 004_retire_orphan_instances.up.sql
--
-- Hasta ahora borrar una plantilla solo ponía is_active = false y dejaba sus instancias
-- pendientes vivas: seguían apareciendo en /tasks y en el sync, y al completarlas la
-- recurrencia las regeneraba para siempre. El código nuevo retira esas instancias al
-- borrar la plantilla (retirePendingInstances); esta migración hace lo mismo con las que
-- ya existían, con tombstone para que los clientes las quiten en su próximo pull.
--
-- Solo toca instancias PENDIENTES de plantillas inactivas. El historial (done/skipped) se
-- conserva. No es reversible: lo borrado era basura, no hay nada que restaurar.

-- Una sola versión nueva para todo el lote: los tombstones comparten row_version.
UPDATE sync_counter SET value = value + 1;

INSERT INTO sync_tombstones (household_id, entity, entity_id, row_version)
SELECT ti.household_id, 'task', ti.id, (SELECT value FROM sync_counter)
FROM task_instances ti
JOIN task_templates tt ON tt.id = ti.template_id
WHERE ti.status = 'pending' AND tt.is_active = false
ON CONFLICT (household_id, entity, entity_id)
DO UPDATE SET row_version = excluded.row_version,
              deleted_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now');

-- generated_from_instance_id apunta a task_instances(id): se suelta antes de borrar.
UPDATE task_instances SET generated_from_instance_id = NULL
WHERE generated_from_instance_id IN (
    SELECT ti.id FROM task_instances ti
    JOIN task_templates tt ON tt.id = ti.template_id
    WHERE ti.status = 'pending' AND tt.is_active = false
);

DELETE FROM task_instances
WHERE id IN (
    SELECT ti.id FROM task_instances ti
    JOIN task_templates tt ON tt.id = ti.template_id
    WHERE ti.status = 'pending' AND tt.is_active = false
);
