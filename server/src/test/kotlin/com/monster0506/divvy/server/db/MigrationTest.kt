package com.monster0506.divvy.server.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {
    private val coreTables = listOf("household", "person", "receipt", "receipt_item", "item_owner", "settlement")

    @Test
    fun `migrations apply cleanly to an empty database`() {
        TestDatabase.db
        val history = TestDatabase.query(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
        )
        assertEquals(listOf("1", "2", "3", "6"), history.map { it["version"] })
        assertTrue(history.all { it["success"] == true })
    }

    @Test
    fun `running migrations again is a no-op`() {
        TestDatabase.db
        DatabaseFactory.connect(TestDatabase.appConfig).close()
        val count = TestDatabase.query("SELECT count(*) AS n FROM flyway_schema_history").single()["n"]
        assertEquals(4L, count)
    }

    @Test
    fun `pgvector extension is enabled`() {
        val ext = TestDatabase.query("SELECT extname FROM pg_extension WHERE extname = 'vector'")
        assertEquals(1, ext.size)
        val distance = TestDatabase.query("SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector AS d").single()["d"]
        assertEquals(1.0, distance)
    }

    @Test
    fun `all core tables exist with timestamps`() {
        coreTables.forEach { table ->
            val columns = TestDatabase.query(
                """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '$table'
                """.trimIndent(),
            ).associate { it["column_name"] to it["data_type"] }
            assertEquals("timestamp with time zone", columns["created_at"], "$table.created_at")
            assertEquals("timestamp with time zone", columns["updated_at"], "$table.updated_at")
        }
    }

    @Test
    fun `no column uses a floating-point type`() {
        val floats = TestDatabase.query(
            """
            SELECT table_name, column_name, data_type FROM information_schema.columns
            WHERE table_schema = 'public' AND data_type IN ('real', 'double precision')
            """.trimIndent(),
        )
        assertEquals(emptyList(), floats)
    }

    @Test
    fun `every money column is bigint minor units`() {
        val money = TestDatabase.query(
            """
            SELECT table_name, column_name, data_type FROM information_schema.columns
            WHERE table_schema = 'public' AND column_name LIKE '%\_minor'
            """.trimIndent(),
        )
        assertTrue(money.size >= 6, "expected money columns, found $money")
        assertTrue(money.all { it["data_type"] == "bigint" }, "non-bigint money column in $money")
    }

    @Test
    fun `every foreign key is backed by an index`() {
        val fks = TestDatabase.query(
            """
            SELECT conrelid::regclass::text AS table_name, conname
            FROM pg_constraint c
            WHERE contype = 'f' AND connamespace = 'public'::regnamespace
              AND NOT EXISTS (
                SELECT 1 FROM pg_index i
                WHERE i.indrelid = c.conrelid
                  AND (
                    -- the index starts with exactly the FK columns, or
                    (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] @> c.conkey
                    -- the index starts with the FK's selective (non-household) column
                    OR (i.indkey[0] = ANY (c.conkey) AND i.indkey[0] <> (
                        SELECT attnum FROM pg_attribute WHERE attrelid = c.conrelid AND attname = 'household_id'))
                  )
              )
            """.trimIndent(),
        )
        assertEquals(emptyList(), fks, "foreign keys without a supporting index")
    }
}