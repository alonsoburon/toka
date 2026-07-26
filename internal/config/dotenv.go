// Package config carga la configuración del entorno.
package config

import (
	"bufio"
	"os"
	"strings"
)

// LoadDotEnv lee un archivo .env y define las variables que falten en el entorno.
//
// Hasta ahora nadie lo hacía: el Makefile copiaba .env.example a .env y el binario
// nunca lo leía, así que la configuración real venía del literal que había escrito
// en db.Connect. Funcionaba en local y habría fallado en silencio al desplegar.
//
// Las variables que ya existen en el entorno ganan: en producción manda lo que
// inyecte el orquestador, no un archivo que se quedó en la imagen.
//
// No es un parser completo de dotenv — no hay interpolación ni valores multilínea,
// que es todo lo que este proyecto necesita. Si algún día hace falta, se cambia por
// una librería; hoy sería una dependencia para veinte líneas.
func LoadDotEnv(path string) error {
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil // sin .env se usa el entorno tal cual, que es lo normal en producción
		}
		return err
	}
	defer f.Close()

	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")

		key, value, found := strings.Cut(line, "=")
		if !found {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)

		// Comillas opcionales alrededor del valor: una contraseña con # o espacios
		// las necesita.
		if len(value) >= 2 && (value[0] == '"' && value[len(value)-1] == '"' ||
			value[0] == '\'' && value[len(value)-1] == '\'') {
			value = value[1 : len(value)-1]
		}

		if key == "" {
			continue
		}
		if _, exists := os.LookupEnv(key); exists {
			continue
		}
		if err := os.Setenv(key, value); err != nil {
			return err
		}
	}
	return sc.Err()
}
