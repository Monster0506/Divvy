package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.repository.RepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExposedMlSettingsRepositoryTest : RepositoryTestBase() {
    private val settings = ExposedMlSettingsRepository(TestDatabase.db.database)

    @Test
    fun `a household that never changed anything gets the defaults`() = runBlocking<Unit> {
        val (home, _) = household("TJ")

        assertEquals(MlSettings(true, 0.85), settings.get(home.id))
    }

    @Test
    fun `saved settings are read back and saving again replaces them`() = runBlocking<Unit> {
        val (home, _) = household("TJ")

        settings.save(home.id, MlSettings(false, 0.6))
        assertEquals(MlSettings(false, 0.6), settings.get(home.id))
        assertEquals(MlSettings(true, 0.975), settings.save(home.id, MlSettings(true, 0.975)))
    }

    @Test
    fun `settings are per household`() = runBlocking<Unit> {
        val (mine, _) = household("TJ")
        val (theirs, _) = household("Pat")

        settings.save(mine.id, MlSettings(true, 0.5))

        assertEquals(0.85, settings.get(theirs.id).autoAssignThreshold)
    }

    @Test
    fun `a threshold outside zero to one is rejected by the database`() = runBlocking<Unit> {
        val (home, _) = household("TJ")

        assertFailsWith<ExposedSQLException> { settings.save(home.id, MlSettings(true, 1.5)) }
    }

    @Test
    fun `deleting a household removes its settings`() = runBlocking<Unit> {
        val (home, _) = household("TJ")
        settings.save(home.id, MlSettings(false, 0.5))

        repos.households.delete(home.id)

        assertEquals(0L, TestDatabase.query("SELECT count(*) AS n FROM household_ml_settings").single()["n"])
    }
}
