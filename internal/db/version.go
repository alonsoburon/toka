package db

import (
	"context"
	"database/sql"
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
