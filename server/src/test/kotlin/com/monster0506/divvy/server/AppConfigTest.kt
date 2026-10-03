package com.monster0506.divvy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {
    private val env = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/divy",
        "DATABASE_USER" to "divy",
        "DATABASE_PASSWORD" to "s3cret",
    )

    @Test
    fun `reads database settings with defaults`() {
        val config = AppConfig.fromEnv(env)

        assertEquals(AppConfig.DEFAULT_PORT, config.port)
        assertEquals("jdbc:postgresql://localhost:5432/divy", config.database.jdbcUrl)
        assertEquals("divy", config.database.user)
        assertEquals(AppConfig.DEFAULT_POOL_SIZE, config.database.maxPoolSize)
    }

    @Test
    fun `reads optional port and pool size`() {
        val config = AppConfig.fromEnv(env + mapOf("PORT" to "9000", "DATABASE_POOL_SIZE" to "4"))

        assertEquals(9000, config.port)
        assertEquals(4, config.database.maxPoolSize)
    }

    @Test
    fun `names every missing variable`() {
        val error = assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("DATABASE_URL" to "jdbc:postgresql://x/y")) }

        assertTrue("DATABASE_USER" in error.message!!)
        assertTrue("DATABASE_PASSWORD" in error.message!!)
    }

    @Test
    fun `rejects a non-postgres url`() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(env + ("DATABASE_URL" to "postgres://localhost/divy")) }
    }

    @Test
    fun `password is not printed`() {
        assertFalse("s3cret" in AppConfig.fromEnv(env).toString())
    }
}