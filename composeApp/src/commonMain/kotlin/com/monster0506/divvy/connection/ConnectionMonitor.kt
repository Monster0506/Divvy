package com.monster0506.divvy.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps one app-wide [status] for the configured server, so screens don't each have to explain
 * that it is offline.
 *
 * [refresh] checks right away and then, while the server is unreachable or rejects the key,
 * retries quietly with backoff. The status only changes when a check gives a different answer,
 * so the banner doesn't flicker between attempts.
 */
class ConnectionMonitor(
    private val scope: CoroutineScope,
    private val check: suspend () -> ConnectionStatus,
    private val retryDelaysMillis: List<Long> = listOf(5_000, 10_000, 30_000, 60_000),
) {
    private val _status = MutableStateFlow(ConnectionStatus.Unknown)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** Checks now, replacing any pending retry. Call on launch and when the server settings change. */
    fun refresh() {
        job?.cancel()
        job = scope.launch {
            var attempt = 0
            while (true) {
                val result = check()
                _status.value = result
                if (result == ConnectionStatus.Online) break
                delay(retryDelaysMillis[minOf(attempt, retryDelaysMillis.lastIndex)])
                attempt++
            }
        }
    }

    /**
     * Called when any API call fails to reach the server or gets a 401. Rechecks only if the app
     * currently thinks it is online; otherwise a retry loop is already running.
     */
    fun reportFailure() {
        if (_status.value == ConnectionStatus.Online) refresh()
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
