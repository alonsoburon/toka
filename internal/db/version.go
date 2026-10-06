package db

import (
	"context"
	"database/sql"
	"time"
)

// NextRowVersion incrementa el contador global y devuelve el nuevo valor.
//
// Se llama dentro de la misma transacción que la escritura, así que el número
// solo se hace visible si la transacción confirma. Como el contador es una sola
// fila, el orden de asignación coincide con el orden de confirmación — que es lo
// que el cursor de sincronización necesita para no saltarse filas.
func NextRowVersion(ctx context.Context, tx *sql.Tx) (int64, error) {
	var v int64
	err := tx.QueryRowContext(ctx, `
		UPDATE sync_counter SET value = value + 1 RETURNING value
	`).Scan(&v)
	return v, err
}

// PruneMutations borra de sync_mutations los registros más viejos que olderThan. La tabla
// guarda la respuesta completa de cada escritura offline para poder deduplicar
// reenvíos, pero un reenvío llega en horas o días, no meses: sin poda crece para siempre.
func PruneMutations(ctx context.Context, database *sql.DB, olderThan time.Duration) (int64, error) {
	cutoff := time.Now().UTC().Add(-olderThan)
	res, err := database.ExecContext(ctx, `DELETE FROM sync_mutations WHERE applied_at < ?`, cutoff)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
