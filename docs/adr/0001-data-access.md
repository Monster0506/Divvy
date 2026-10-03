# 0001: Data access library

Status: accepted

## Decision

The server uses PostgreSQL with HikariCP for pooling, Flyway for migrations and JetBrains Exposed (SQL DSL) for queries.

## Rationale

- Exposed is Kotlin-first, has no code generation step and fits the existing Gradle setup. jOOQ needs a generated schema, which adds build complexity for a small team.
- The Exposed DSL keeps queries close to SQL, so we can still write set-based queries for balances and ledgers without an ORM hiding them.
- Flyway keeps the schema in plain versioned SQL files under `server/src/main/resources/db/migration`, applied automatically at startup by `DatabaseFactory`.
- HikariCP is the standard JDBC pool and is configured from `AppConfig` (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`).

## Consequences

- Schema changes are made only through new Flyway migrations, never by Exposed.
- Tests use an in-memory H2 database in PostgreSQL mode for plumbing, and should use a real Postgres for anything Postgres specific.
