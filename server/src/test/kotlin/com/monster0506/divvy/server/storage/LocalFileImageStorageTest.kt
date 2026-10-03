package com.monster0506.divvy.server.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFileImageStorageTest {
    private fun newStorage(): Pair<LocalFileImageStorage, Path> {
        val dir = Files.createTempDirectory("divvy-storage")
        dir.toFile().deleteOnExit()
        return LocalFileImageStorage(dir.resolve("images")) to dir
    }

    @Test
    fun imageRoundTrips() {
        val (storage, _) = newStorage()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val key = storage.put(bytes)
        assertContentEquals(bytes, storage.get(key))
    }

    @Test
    fun eachPutGetsItsOwnKey() {
        val (storage, _) = newStorage()
        val a = storage.put(byteArrayOf(1))
        val b = storage.put(byteArrayOf(2))
        assertNotEquals(a, b)
        assertContentEquals(byteArrayOf(1), storage.get(a))
        assertContentEquals(byteArrayOf(2), storage.get(b))
    }

    @Test
    fun deleteRemovesImage() {
        val (storage, _) = newStorage()
        val key = storage.put(byteArrayOf(9))
        assertTrue(storage.delete(key))
        assertNull(storage.get(key))
        assertFalse(storage.delete(key))
    }

    @Test
    fun unknownKeyReturnsNull() {
        val (storage, _) = newStorage()
        assertNull(storage.get("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun pathTraversalKeysAreRejected() {
        val (storage, dir) = newStorage()
        val secret = dir.resolve("secret.txt")
        Files.write(secret, byteArrayOf(7))
        val attempts = listOf(
            "../secret.txt",
            "..\\secret.txt",
            "../../secret.txt",
            secret.toAbsolutePath().toString(),
            "",
            ".",
            "..",
            "a/b",
        )
        for (key in attempts) {
            assertNull(storage.get(key), "get should reject '$key'")
            assertFalse(storage.delete(key), "delete should reject '$key'")
        }
        assertTrue(Files.exists(secret))
        assertEquals(1, Files.readAllBytes(secret).size)
    }
}
