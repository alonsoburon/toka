package server

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// limits protege el servidor de un aluvión. Es deliberadamente simple y en
// memoria: para dos personas alcanza y no suma dependencias.
//
//   - token bucket por IP (rate sostenido, ráfaga)
//   - tope de peticiones concurrentes (la e2-micro tiene 1 vCPU)
//   - tope de tamaño del body
//
// El servidor corre detrás de Caddy. Caddy añade la IP real al FINAL de
// X-Forwarded-For; lo que venga antes lo pudo poner el cliente, así que se toma
// el último valor. Si no hay header (desarrollo local), se usa RemoteAddr.
type limits struct {
	mu        sync.Mutex
	buckets   map[string]*bucket
	rate      float64
	burst     float64
	maxBody   int64
	sem       chan struct{}
	lastSweep time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newLimits(rate, burst float64, maxBody int64, maxConcurrent int) *limits {
	return &limits{
		buckets:   make(map[string]*bucket),
		rate:      rate,
		burst:     burst,
		maxBody:   maxBody,
		sem:       make(chan struct{}, maxConcurrent),
		lastSweep: time.Now(),
	}
}

func (l *limits) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)

		// Loopback es tráfico local de confianza (dev, smoke tests, curl desde el
		// propio host). No tiene sentido limitarlo.
		if isLoopback(ip) {
			next.ServeHTTP(w, r)
			return
		}

		if !l.allow(ip) {
			w.Header().Set("Retry-After", "1")
			http.Error(w, `{"error":"rate limited"}`, http.StatusTooManyRequests)
			return
		}

		select {
		case l.sem <- struct{}{}:
			defer func() { <-l.sem }()
		default:
			http.Error(w, `{"error":"busy"}`, http.StatusServiceUnavailable)
			return
		}

		if l.maxBody > 0 {
			r.Body = http.MaxBytesReader(w, r.Body, l.maxBody)
		}
		next.ServeHTTP(w, r)
	})
}

func (l *limits) allow(ip string) bool {
	now := time.Now()

	l.mu.Lock()
	defer l.mu.Unlock()

	b := l.buckets[ip]
	if b == nil {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[ip] = b
	} else {
		b.tokens += now.Sub(b.last).Seconds() * l.rate
		if b.tokens > l.burst {
			b.tokens = l.burst
		}
		b.last = now
	}

	l.sweepLocked(now)

	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// sweepLocked descarta los buckets inactivos para que el mapa no crezca sin
// límite si alguien rota IPs. Corre como mucho una vez por minuto.
func (l *limits) sweepLocked(now time.Time) {
	if now.Sub(l.lastSweep) < time.Minute {
		return
	}
	l.lastSweep = now
	for k, b := range l.buckets {
		if now.Sub(b.last) > 10*time.Minute {
			delete(l.buckets, k)
		}
	}
}

func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
			return ip
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func isLoopback(ip string) bool {
	if ip == "localhost" {
		return true
	}
	parsed := net.ParseIP(ip)
	return parsed != nil && parsed.IsLoopback()
}
