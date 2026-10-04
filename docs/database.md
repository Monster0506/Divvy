# Database

The server uses PostgreSQL 16 with pgvector, HikariCP, Flyway and Exposed. See
[ADR 0001](adr/0001-data-access.md) for why.

## Configuration

| Variable | Required | Example |
|---|---|---|
| `DATABASE_URL` | yes | `jdbc:postgresql://localhost:5432/divy` |
| `DATABASE_USER` | yes | `divy` |
| `DATABASE_PASSWORD` | yes | `divy` |
| `DATABASE_POOL_SIZE` | no (default 10) | `10` |
| `PORT` | no (default 8080) | `8080` |

## Running locally

```bash
docker run -d --name divy-db -p 5432:5432 \
  -e POSTGRES_USER=divy -e POSTGRES_PASSWORD=divy -e POSTGRES_DB=divy \
  pgvector/pgvector:pg16

DATABASE_URL=jdbc:postgresql://localhost:5432/divy DATABASE_USER=divy DATABASE_PASSWORD=divy \
  ./gradlew :server:run
```

On startup the log shows Flyway applying pending migrations, then the summary line:

```
INFO  o.f.core.internal.command.DbMigrate - Successfully applied 1 migration to schema "public", now at version v1
INFO  app.divy.server.db.DatabaseFactory - Database migrations: 1 applied, schema at version 1
INFO  io.ktor.server.Application - Responding at http://0.0.0.0:8080
```

On later starts it reports `0 applied`.

## Migrations

Add a new file `server/src/main/resources/db/migration/V<next>__<description>.sql`. Never edit a
merged migration: Flyway checks checksums and the server will refuse to start.

## Tests

`./gradlew :server:test` starts a throwaway `pgvector/pgvector:pg16` container with
Testcontainers, so Docker must be running. CI runs the same command
(`.github/workflows/server-tests.yml`).