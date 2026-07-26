package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
)

// Antes los tokens y los invite codes salían de math/rand. El generador global de
// math/rand no es criptográfico: con unos pocos valores observados se puede predecir
// el siguiente, y aquí un invite code adivinado alcanza para entrar a la casa de
// otra familia. Todo lo que sea credencial sale de crypto/rand.

// NewToken devuelve un bearer token de 256 bits. Es lo único que ve el cliente;
// la base guarda solo su hash.
func NewToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		// crypto/rand.Read no falla en la práctica en Linux; si lo hiciera,
		// seguir adelante significaría emitir una credencial débil.
		panic("crypto/rand no disponible: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// inviteAlphabet omite los caracteres que se confunden al dictar un código por
// teléfono o copiarlo de una pantalla: I, O, 0 y 1.
//
// Tiene exactamente 32 símbolos, que divide a 256, así que tomar el módulo de un
// byte aleatorio no introduce sesgo — con un alfabeto de otro tamaño habría que
// rechazar y reintentar.
const inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

// NewInviteCode devuelve un código de 10 caracteres (50 bits).
//
// Los 8 caracteres de antes eran 40 bits, pero salían de math/rand, que es lo que
// realmente importaba. A 50 bits, adivinar uno a fuerza bruta contra un servidor
// con rate limiting no es una vía de entrada.
func NewInviteCode() string {
	const n = 10
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic("crypto/rand no disponible: " + err.Error())
	}
	out := make([]byte, n)
	for i, v := range b {
		out[i] = inviteAlphabet[int(v)%len(inviteAlphabet)]
	}
	return string(out)
}

// HashToken es lo que se guarda en people.token_hash y lo que se compara al entrar.
//
// sha256 pelado, sin salt ni derivación lenta, y a propósito: un token de 256 bits
// generado con crypto/rand no es adivinable por diccionario, así que argon2 o bcrypt
// solo añadirían latencia a cada petición autenticada. El razonamiento sería el
// contrario si esto fuera una contraseña elegida por una persona.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// EqualToken compara en tiempo constante. Las comparaciones de credenciales no
// deberían filtrar por cuánto tardan en fallar.
func EqualToken(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
