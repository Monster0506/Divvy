package com.monster0506.divvy.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    private const val MIGRATIONS = "classpath:db/migration"

    fun init(config: AppConfig, migrations: String = MIGRATIONS): DataSource {
        val dataSource = createDataSource(config)
        migrate(dataSource, migrations)
        Database.connect(dataSource)
        return dataSource
    }

    fun createDataSource(config: AppConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 10
            },
        )

    fun migrate(dataSource: DataSource, location: String = MIGRATIONS): Int =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .migrate()
            .migrationsExecuted
}
