package com.monster0506.divvy.server.storage

interface ImageStorage {
    /** Stores the bytes under a newly generated key and returns that key. */
    fun put(bytes: ByteArray): String

    /** Returns the stored bytes, or null if the key is unknown or malformed. */
    fun get(key: String): ByteArray?

    /** Deletes the image and returns true if it existed. */
    fun delete(key: String): Boolean
}
