# ADR 0001: Data access and migrations for the server

- Status: Accepted
- Date: 2026-10-03
- Issue: Configure database connection, migrations and data-access library (part of #2)

## Context

The Ktor server stores households, receipts, item ownership and settlements in PostgreSQL 16
with the pgvector extension. We need to decide on:

- how the schema is created and changed over time,
- how the server connects to the database,
- which library the server uses to query it.

Constraints:

- Kotlin is the language for both the server and the mobile app. Code that reads well to
  Kotlin developers is worth more than raw feature count.
- The schema relies on PostgreSQL features: composite foreign keys, check constraints,
  partial indexes, triggers and the `vector` type. The schema must be the real source of truth,
  not something an ORM infers.
- Money is integer minor units, and receipts are the record of what happened. The SQL that runs
  must be predictable and easy to review.
- Deployment is a single Docker Compose stack. There is no separate migration job or CI step
  against production.

## Decision

1. **Flyway for migrations, run on server startup.** SQL migrations live in
   `server/src/main/resources/db/migration` as `V<n>__<description>.sql`. `DatabaseFactory`
   runs `Flyway.migrate()` before Ktor accepts requests. Startup fails if a migration fails or a
   merged migration was edited (checksum mismatch).
2. **HikariCP connection pool** built from `AppConfig.database`. Settings come from the
   `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` and `DATABASE_POOL_SIZE` environment
   variables. Auto-commit is off, and isolation is READ COMMITTED.
3. **Exposed (DSL, not DAO) for data access**, behind repository interfaces. Exposed table
   objects describe columns only. `SchemaUtils` never creates or alters tables. Queries run on
   `Dispatchers.IO` through a small `dbQuery` helper.
4. **PostgreSQL JDBC driver**. Tests use Testcontainers with the same `pgvector/pgvector:pg16`
   image as production.

## Alternatives considered

**jOOQ.** jOOQ has strong type safety, generated from the real schema, and the best SQL
coverage. It needs a code-generation step that runs against a live database or the migration
files, which adds build complexity. Its PostgreSQL-specific features are free, but the
commercial licence matters only for other databases. We may revisit it if queries outgrow
Exposed, for example complex balance reports. The repository interfaces keep that switch local.

**Exposed DAO / an ORM like Hibernate.** Entity caching and implicit loading hide the SQL that
runs. That is a poor fit for financial records and makes N+1 queries easy to miss. Hibernate
also pulls in Java-centric idioms and a much heavier runtime.

**Raw JDBC.** No dependency and full control, but every query needs hand-written mapping and
parameter binding, which is a lot of boilerplate for a small team.

**Liquibase.** Equivalent capability. Flyway's plain versioned SQL files are simpler to read
and review, and we have no need for Liquibase's database-agnostic changelogs.

**Migrations as a separate deploy step.** This is safer for multi-instance deployments, but we
run one server instance. Migrating on startup keeps the server and schema in lockstep, with one
fewer moving part.

## Consequences

- A broken or edited migration stops the server from starting. That is intentional: it fails
  loudly rather than serving against an unknown schema.
- Migrations are forward-only. A mistake is fixed with a new migration, never by editing a
  merged one.
- Exposed table objects must be kept in sync with the migrations by hand. Repository
  integration tests against a real PostgreSQL container catch drift.
- If the server ever runs as several replicas, Flyway's lock table serializes concurrent
  startups. We may still prefer to move migrations to a one-off job at that point.
- `CREATE EXTENSION vector` needs a superuser on first run. The `POSTGRES_USER` of the
  pgvector image satisfies this. A less-privileged application role would need the extension
  created ahead of time.