package handler

import (
	"context"
	"database/sql"
	"errors"
	"unicode/utf8"
)

func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func nullIfEmptyPtr(s *string) *string {
	if s == nil || *s == "" {
		return nil
	}
	return s
}

// Límites de entrada. El body ya está acotado a 1 MB por el middleware; esto evita que
// una sola fila ocupe todo ese megabyte.
const (
	maxNameLen  = 120
	maxTextLen  = 2000 // description y notes
	maxColorLen = 16
	maxEmojiLen = 16

	// maxRecurrenceDays evita desbordar time.Duration (días * 24h) y fechas absurdas.
	maxRecurrenceDays = 3650
)

// tooLong dice si s excede max caracteres (runes, no bytes: los emojis pesan más de uno).
func tooLong(s string, max int) bool { return utf8.RuneCountInString(s) > max }

func tooLongPtr(s *string, max int) bool { return s != nil && tooLong(*s, max) }

// validRecurrence acepta nil (sin recurrencia) o un valor en 1..maxRecurrenceDays.
func validRecurrence(days *int) bool {
	return days == nil || (*days >= 1 && *days <= maxRecurrenceDays)
}

// personInHousehold comprueba que id (si viene) es una persona del household hid.
//
// Sin esto, las columnas assigned_to_id / preferred_assignee_id aceptan el id de una
// persona de OTRA familia —la FK solo exige que exista—, lo que filtra su nombre y su
// avatar por los JOIN y bloquea que esa familia pueda borrarla.
func personInHousehold(ctx context.Context, q interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, hid int64, id *int64) (bool, error) {
	if id == nil {
		return true, nil
	}
	var one int
	err := q.QueryRowContext(ctx,
		`SELECT 1 FROM people WHERE id = ? AND household_id = ?`, *id, hid).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// resolveTemplateID traduce el id de una plantilla creada sin conexión. El cliente la
// muestra con un id local negativo hasta que el servidor confirma su client_id; las
// ediciones que encola mientras tanto llevan ese client_id para poder encontrarla aquí.
// Devuelve ok=false si no hay forma de resolverla.
func resolveTemplateID(ctx context.Context, tx *sql.Tx, hid, id int64, clientID string) (int64, bool, error) {
	if id > 0 {
		return id, true, nil
	}
	if clientID == "" {
		return 0, false, nil
	}
	var real int64
	err := tx.QueryRowContext(ctx,
		`SELECT id FROM task_templates WHERE household_id = ? AND client_id = ?`, hid, clientID).Scan(&real)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	return real, err == nil, err
}

// retirePendingInstances borra las instancias pendientes de una plantilla dada de baja,
// con tombstone para que los clientes offline también las quiten. Las ya resueltas
// (done/skipped) se conservan: son el historial.
func retirePendingInstances(ctx context.Context, tx *sql.Tx, hid, templateID, rowVersion int64) error {
	rows, err := tx.QueryContext(ctx, `
		SELECT id FROM task_instances
		WHERE template_id = ? AND household_id = ? AND status = 'pending'
	`, templateID, hid)
	if err != nil {
		return err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	for _, id := range ids {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO sync_tombstones (household_id, entity, entity_id, row_version)
			VALUES (?, 'task', ?, ?)
			ON CONFLICT (household_id, entity, entity_id)
			DO UPDATE SET row_version = excluded.row_version,
			              deleted_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now')
		`, hid, id, rowVersion); err != nil {
			return err
		}
		// generated_from_instance_id apunta a task_instances(id): se suelta antes de borrar.
		// Es una columna interna (no viaja en el sync), así que esta escritura no versiona
		// la fila hija: si ya está resuelta es historial y su row_version no tiene por qué
		// cambiar.
		if _, err := tx.ExecContext(ctx, `
			UPDATE task_instances SET generated_from_instance_id = NULL
			WHERE generated_from_instance_id = ? AND household_id = ?
		`, id, hid); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `
			DELETE FROM task_instances WHERE id = ? AND household_id = ?
		`, id, hid); err != nil {
			return err
		}
	}
	return nil
}
