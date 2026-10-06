-- 004_retire_orphan_instances.down.sql
--
-- Sin efecto a propósito: la migración borró instancias pendientes de plantillas ya dadas
-- de baja (datos huérfanos) y no hay nada que restaurar. Existe para mantener el par
-- up/down que exige el proyecto.
SELECT 1;
