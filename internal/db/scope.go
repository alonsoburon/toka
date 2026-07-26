package db

import (
	"context"
	"strconv"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Las policies de RLS de la migración 002 leen tres settings de sesión de Postgres.
// Todos se fijan con is_local => true, o sea que valen hasta el COMMIT/ROLLBACK y no
// sobreviven a la devolución de la conexión al pool. Eso es lo que hace que sea
// seguro: una petición no puede heredar el contexto de la anterior.
//
// Sin setting, current_setting(..., true) es NULL, las policies dan falso y no se ve
// ninguna fila. El caso por defecto es negar, no permitir.

// BeginScoped abre una transacción atada a un household. Todo lo que se consulte o
// escriba dentro queda filtrado por ese household por el motor, no por el WHERE.
func BeginScoped(ctx context.Context, pool *pgxpool.Pool, householdID int64) (pgx.Tx, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	if err := SetHousehold(ctx, tx, householdID); err != nil {
		_ = tx.Rollback(ctx)
		return nil, err
	}
	return tx, nil
}

// BeginWithTokenHash abre una transacción que solo puede leer la fila de people
// cuyo token coincide. Es el paso de login: todavía no se sabe a qué household
// pertenece quien pregunta, así que no se puede acotar por household.
func BeginWithTokenHash(ctx context.Context, pool *pgxpool.Pool, tokenHash string) (pgx.Tx, error) {
	return beginWith(ctx, pool, "app.token_hash", tokenHash)
}

// BeginWithInviteCode hace lo mismo para el join: deja ver el household cuyo
// invite_code coincide exactamente, y ninguno más.
func BeginWithInviteCode(ctx context.Context, pool *pgxpool.Pool, code string) (pgx.Tx, error) {
	return beginWith(ctx, pool, "app.invite_code", code)
}

func beginWith(ctx context.Context, pool *pgxpool.Pool, key, value string) (pgx.Tx, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	if err := setLocal(ctx, tx, key, value); err != nil {
		_ = tx.Rollback(ctx)
		return nil, err
	}
	return tx, nil
}

// SetHousehold cambia el household activo dentro de una transacción ya abierta.
// Lo usan los dos handlers de onboarding, que empiezan sin household y lo adoptan
// en cuanto lo conocen.
func SetHousehold(ctx context.Context, tx pgx.Tx, householdID int64) error {
	return setLocal(ctx, tx, "app.household_id", strconv.FormatInt(householdID, 10))
}

func setLocal(ctx context.Context, tx pgx.Tx, key, value string) error {
	_, err := tx.Exec(ctx, "SELECT set_config($1, $2, true)", key, value)
	return err
}

// NextHouseholdID reserva un id de household antes de insertarlo.
//
// Es la vuelta al problema del huevo y la gallina de crear un household: la policy
// exige que la fila insertada pertenezca al household activo, pero el id lo asigna
// la secuencia durante el INSERT. Pidiendo el nextval primero podemos declarar el
// household y recién entonces insertarlo, sin necesidad de una policy de excepción
// que deje escribir fuera de todo contexto.
func NextHouseholdID(ctx context.Context, tx pgx.Tx) (int64, error) {
	var id int64
	err := tx.QueryRow(ctx, "SELECT nextval(pg_get_serial_sequence('households','id'))").Scan(&id)
	return id, err
}
