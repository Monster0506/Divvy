package com.monster0506.divvy.server.storage

/** Creates the real storage on first use, so startup and tests that never touch images leave the directory alone. */
class LazyImageStorage(create: () -> ImageStorage) : ImageStorage {
    private val delegate by lazy(create)

    override fun put(bytes: ByteArray): String = delegate.put(bytes)

    override fun get(key: String): ByteArray? = delegate.get(key)

    override fun delete(key: String): Boolean = delegate.delete(key)
}
