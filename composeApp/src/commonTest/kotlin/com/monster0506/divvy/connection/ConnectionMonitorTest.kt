package com.monster0506.divvy.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {
    private class FakeServer(vararg answers: ConnectionStatus) {
        private val queue = ArrayDeque(answers.toList())
        var calls = 0
        var last = answers.last()
        suspend fun check(): ConnectionStatus {
            calls++
            if (queue.isNotEmpty()) last = queue.removeFirst()
            return last
        }
    }

    private fun TestScope.monitor(server: FakeServer) =
        ConnectionMonitor(backgroundScope, server::check, retryDelaysMillis = listOf(1_000, 5_000))

    @Test
    fun startsUnknownUntilChecked() = runTest {
        val m = monitor(FakeServer(ConnectionStatus.Online))
        assertEquals(ConnectionStatus.Unknown, m.status.value)
        m.refresh()
        runCurrent()
        assertEquals(ConnectionStatus.Online, m.status.value)
    }

    @Test
    fun onlineStopsChecking() = runTest {
        val server = FakeServer(ConnectionStatus.Online)
        monitor(server).refresh()
        advanceTimeBy(60_000)
        assertEquals(1, server.calls)
    }

    @Test
    fun unreachableRetriesWithBackoffAndClearsWhenServerReturns() = runTest {
        val server = FakeServer(ConnectionStatus.Unreachable, ConnectionStatus.Unreachable, ConnectionStatus.Online)
        val m = monitor(server)
        m.refresh()
        runCurrent()
        assertEquals(ConnectionStatus.Unreachable, m.status.value)

        advanceTimeBy(1_001)
        assertEquals(2, server.calls)
        assertEquals(ConnectionStatus.Unreachable, m.status.value)

        advanceTimeBy(4_000)
        assertEquals(2, server.calls, "second retry waits the longer delay")
        advanceTimeBy(1_001)
        assertEquals(3, server.calls)
        assertEquals(ConnectionStatus.Online, m.status.value)
    }

    @Test
    fun backoffStaysAtTheLastDelay() = runTest {
        val server = FakeServer(ConnectionStatus.Unreachable)
        monitor(server).refresh()
        runCurrent()
        advanceTimeBy(1_000 + 5_000 + 5_000 + 5_000 + 1)
        assertEquals(5, server.calls)
    }

    @Test
    fun rejectedKeyIsReported() = runTest {
        val m = monitor(FakeServer(ConnectionStatus.KeyRejected))
        m.refresh()
        runCurrent()
        assertEquals(ConnectionStatus.KeyRejected, m.status.value)
    }

    @Test
    fun refreshReplacesPendingRetry() = runTest {
        val server = FakeServer(ConnectionStatus.Unreachable, ConnectionStatus.Online)
        val m = monitor(server)
        m.refresh()
        runCurrent()
        m.refresh()
        runCurrent()
        assertEquals(ConnectionStatus.Online, m.status.value)
        advanceTimeBy(60_000)
        assertEquals(2, server.calls, "the cancelled retry never fires")
    }

    @Test
    fun failureWhileOnlineTriggersRecheck() = runTest {
        val server = FakeServer(ConnectionStatus.Online, ConnectionStatus.Unreachable)
        val m = monitor(server)
        m.refresh()
        runCurrent()
        m.reportFailure()
        runCurrent()
        assertEquals(ConnectionStatus.Unreachable, m.status.value)
        assertEquals(2, server.calls)
    }

    @Test
    fun failureWhileAlreadyOfflineDoesNotRestartRetries() = runTest {
        val server = FakeServer(ConnectionStatus.Unreachable)
        val m = monitor(server)
        m.refresh()
        runCurrent()
        m.reportFailure()
        m.reportFailure()
        runCurrent()
        assertEquals(1, server.calls)
    }

    @Test
    fun stopCancelsRetries() = runTest {
        val server = FakeServer(ConnectionStatus.Unreachable)
        val m = monitor(server)
        m.refresh()
        runCurrent()
        m.stop()
        advanceTimeBy(60_000)
        assertEquals(1, server.calls)
    }
}
