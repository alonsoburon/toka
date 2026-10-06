# Imagen de Toka para Podman. El servidor lee db/migrations y db/seed.sql del disco en
# runtime, así que se copian junto al binario y se ejecuta desde /app.
FROM docker.io/library/golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/toka .

FROM docker.io/library/alpine:3.22
RUN adduser -D -u 10001 toka && mkdir -p /data && chown toka /data
WORKDIR /app
COPY --from=build /out/toka /app/toka
COPY db/migrations /app/db/migrations
USER toka
# El seed (db/seed.sql) NO se incluye a propósito: es solo para desarrollo.
ENV TOKA_DB=/data/toka.db PORT=3000
EXPOSE 3000
ENTRYPOINT ["/app/toka", "-env", ""]
