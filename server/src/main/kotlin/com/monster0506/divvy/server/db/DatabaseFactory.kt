package com.monster0506.divvy.server.db

import com.monster0506.divvy.server.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.io.Closeable

/** An open, migrated database. Close it on shutdown to release the connection pool. */
class DivyDatabase internal constructor(
    private val dataSource: HikariDataSource,
    val database: Database,
    /** Number of migrations Flyway applied during this startup (0 when already up to date). */
    val migrationsApplied: Int,
    /** Schema version after migrating, e.g. `1`. */
    val schemaVersion: String?,
) : Closeable {
    override fun close() = dataSource.close()
}

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /**
     * Builds a Hikari pool from [config], applies pending Flyway migrations from
     * `classpath:db/migration`, and connects Exposed. Fails fast if the database is unreachable or
     * a migration fails, so the server never serves requests against an unknown schema.
     */
    fun connect(config: AppConfig): DivyDatabase = connect(config.database)

    fun connect(settings: AppConfig.DatabaseSettings): DivyDatabase {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                poolName = "divy-db"
                jdbcUrl = settings.jdbcUrl
                username = settings.user
                password = settings.password
                maximumPoolSize = settings.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            },
        )
        try {
            val result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load()
                .migrate()
            log.info(
                "Database migrations: {} applied, schema at version {}",
                result.migrationsExecuted,
                result.targetSchemaVersion ?: result.initialSchemaVersion,
            )
            return DivyDatabase(
                dataSource = dataSource,
                database = Database.connect(dataSource),
                migrationsApplied = result.migrationsExecuted,
                schemaVersion = result.targetSchemaVersion ?: result.initialSchemaVersion,
            )
        } catch (e: Exception) {
            dataSource.close()
            throw e
        }
    }
}

/** Runs [block] in a transaction on the IO dispatcher. Repositories use this for every query. */
suspend fun <T> Database.dbQuery(block: JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { suspendTransaction(this@dbQuery) { block() } }