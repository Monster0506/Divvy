package com.monster0506.divvy.server.db

import com.monster0506.divvy.server.AppConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseFactoryTest {

    @Test
    fun `connecting applies migrations and then is a no-op`() {
        // The first connect in this JVM may or may not be the one that migrates, depending on test
        // order, so check the end state and that a further connect applies nothing.
        DatabaseFactory.connect(TestDatabase.appConfig).close()

        DatabaseFactory.connect(TestDatabase.appConfig).use { db ->
            assertEquals(0, db.migrationsApplied)
            assertEquals(TestDatabase.migrationVersions().max().toString(), db.schemaVersion)
            val applied = transaction(db.database) {
                exec("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL") { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
            assertEquals(TestDatabase.migrationVersions().size, applied)
        }
    }

    @Test
    fun `pgvector is enabled by the first migration`() {
        DatabaseFactory.connect(TestDatabase.appConfig).use { db ->
            val ext = transaction(db.database) {
                exec("SELECT extname FROM pg_extension WHERE extname = 'vector'") { rs -> rs.next() }
            }
            assertEquals(true, ext)
        }
    }

    @Test
    fun `queries run through the pool via dbQuery`() = runBlocking {
        DatabaseFactory.connect(TestDatabase.appConfig).use { db ->
            val one = db.database.dbQuery { exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) } }
            assertEquals(1, one)
        }
    }

    @Test
    fun `an unreachable database fails startup`() {
        val bad = AppConfig.DatabaseSettings(
            jdbcUrl = "jdbc:postgresql://127.0.0.1:1/divy?connectTimeout=2",
            user = "nobody",
            password = "nothing",
        )
        assertFailsWith<Exception> { DatabaseFactory.connect(bad) }
    }
}