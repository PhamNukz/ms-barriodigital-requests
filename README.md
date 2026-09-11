# ms-barriodigital-requests

Microservicio de dominio: gestiona los **trámites** (creación, cambio de estado,
listado). Valida el JWT de Azure AD y autoriza por rol. Al confirmar un cambio de
estado, publica un evento a RabbitMQ (notificaciones) y a Kafka (auditoría/reportes)
**después** del commit en base de datos.

## Cómo correr local

Requiere Java 17, Maven y una Oracle accesible (o correr todo junto con
`barriodigital-infra`, que ya trae Oracle en Docker).

```bash
./mvnw spring-boot:run
```

Levanta en `http://localhost:8081`. Necesita `ms-barriodigital-catalog` (`:8082`)
para validar el tipo de trámite, y RabbitMQ/Kafka corriendo si se quiere probar el
flujo completo de eventos.

Tests: `./mvnw verify` — corren contra H2 en memoria con JWT mockeado, no
necesitan Oracle ni Azure AD real.

## Variables de entorno

| Variable | Default | Descripción |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_SERVICE` | `localhost` / `1521` / `XEPDB1` | Conexión a Oracle |
| `DB_USER` | `barriodigital_requests` | Usuario Oracle dedicado a este servicio |
| `DB_PASSWORD` | — | Password de ese usuario |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | Broker RabbitMQ |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Credenciales RabbitMQ |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers Kafka (coma-separado si son varios) |
| `MS_CATALOG_URL` | `http://localhost:8082` | URL base de `ms-barriodigital-catalog` |
| `AAD_ISSUER_URI` | — | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` |
| `AAD_API_CLIENT_ID` | — | Client ID del App Registration `barriodigital-api` |
| `AAD_REQUIRED_SCOPE` | `access_as_user` | Scope exigido en el token |

Las migraciones de esquema están en `src/main/resources/db/migration` (Flyway).

## Docker

```bash
docker build -t ms-barriodigital-requests .
docker run -p 8081:8081 --env-file .env ms-barriodigital-requests
```

Imagen publicada automáticamente en cada push a `main`:
`ghcr.io/phamnukz/ms-barriodigital-requests:latest`.
