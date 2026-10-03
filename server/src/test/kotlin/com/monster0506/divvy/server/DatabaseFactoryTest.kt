package com.monster0506.divvy.server

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseFactoryTest {
    private val config = AppConfig(
        version = "test",
        databaseUrl = "jdbc:h2:mem:divvy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        databaseUser = "sa",
        databasePassword = "",
        mlServiceUrl = "http://localhost:8000",
        apiKey = "key",
    )

    @Test
    fun appliesMigrationsOnStartupOnce() {
        val dataSource = DatabaseFactory.init(config, "classpath:db/testmigration")
        assertEquals(0, DatabaseFactory.migrate(dataSource, "classpath:db/testmigration"))
        dataSource.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM smoke").use {
                it.next()
                assertEquals(0, it.getInt(1))
            }
        }
    }
}
