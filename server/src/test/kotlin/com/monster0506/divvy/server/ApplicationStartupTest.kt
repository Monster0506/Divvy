package com.monster0506.divvy.server

import com.monster0506.divvy.server.db.DivyDatabase
import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.repository.HouseholdRepository
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApplicationStartupTest {

    @Test
    fun `server starts, migrates and can query the database`() = testApplication {
        var database: DivyDatabase? = null
        var households: HouseholdRepository? = null
        // An empty config stops testApplication from also loading module() from application.conf.
        environment { config = MapApplicationConfig() }
        application {
            database = configureDatabase(TestDatabase.appConfig)
            households = repositories.households
        }
        startApplication()

        assertNotNull(households)
        val db = assertNotNull(database)
        assertEquals("4", db.schemaVersion)
        assertEquals(1, transaction(db.database) { exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) } })
    }
}