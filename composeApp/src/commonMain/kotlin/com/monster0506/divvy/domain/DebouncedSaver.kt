package com.monster0506.divvy.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Saves only the latest value per key, after [delayMs] without a newer one. */
class DebouncedSaver<K, V>(
    private val scope: CoroutineScope,
    private val delayMs: Long = 400,
    private val save: suspend (K, V) -> Unit,
) {
    private val jobs = mutableMapOf<K, Job>()

    fun submit(key: K, value: V) {
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            delay(delayMs)
            save(key, value)
        }
    }
}
