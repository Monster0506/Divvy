package com.monster0506.divvy.server.db

import com.monster0506.divvy.server.AppConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One PostgreSQL 16 + pgvector container shared by every test in the JVM, migrated once
 * through the same [DatabaseFactory.connect] path the server uses on startup.
 * Testcontainers' Ryuk sidecar removes the container when the test JVM exits.
 */
object TestDatabase {
    private val image = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(image).apply { start() }
    }

    val appConfig: AppConfig by lazy {
        AppConfig(
            port = 0,
            database = AppConfig.DatabaseSettings(container.jdbcUrl, container.username, container.password, maxPoolSize = 4),
        )
    }

    val db: DivyDatabase by lazy { DatabaseFactory.connect(appConfig) }

    /** The versions of the `V<n>__*.sql` migrations on the classpath, so tests need no hard-coded list. */
    fun migrationVersions(): List<Int> {
        val folder = checkNotNull(TestDatabase::class.java.classLoader.getResource("db/migration")) { "no migrations" }
        return java.io.File(folder.toURI()).listFiles().orEmpty()
            .mapNotNull { Regex("""^V(\d+)__""").find(it.name)?.groupValues?.get(1)?.toInt() }
            .sorted()
    }

    /** Removes all rows so each test starts from an empty, fully migrated schema. */
    fun reset() {
        transaction(db.database) {
            exec("TRUNCATE household, item_owner_history RESTART IDENTITY CASCADE")
        }
    }

    /** Executes a statement that returns no rows. */
    fun execute(sql: String) {
        transaction(db.database) { exec(sql) }
    }

    /** Runs a query and returns each row as a column-name map. For schema assertions. */
    fun query(sql: String): List<Map<String, Any?>> = transaction(db.database) {
        exec(sql) { rs ->
            val meta = rs.metaData
            buildList {
                while (rs.next()) {
                    add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                }
            }
        }.orEmpty()
    }
}