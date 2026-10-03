package com.monster0506.divvy.server.storage

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID

class LocalFileImageStorage(directory: Path) : ImageStorage {
    private val root: Path = directory.toAbsolutePath().normalize().also { Files.createDirectories(it) }

    override fun put(bytes: ByteArray): String {
        val key = UUID.randomUUID().toString()
        Files.write(root.resolve(key), bytes)
        return key
    }

    override fun get(key: String): ByteArray? {
        val path = pathFor(key) ?: return null
        return try {
            Files.readAllBytes(path)
        } catch (e: NoSuchFileException) {
            null
        }
    }

    override fun delete(key: String): Boolean {
        val path = pathFor(key) ?: return false
        return Files.deleteIfExists(path)
    }

    // Only canonical UUIDs are accepted, so a key can never contain path separators or dots.
    private fun pathFor(key: String): Path? {
        val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return null
        if (uuid.toString() != key) return null
        return root.resolve(key)
    }
}
