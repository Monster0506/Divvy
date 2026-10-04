package com.monster0506.divvy.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.api.apiMessage
import com.monster0506.divvy.ui.state.validateMemberName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the household's members in step with the server: loads them, saves renames after a short pause,
 * and archives people. A refused change puts the last saved value back and sets [message].
 */
class MemberSyncController(
    private val scope: CoroutineScope,
    private val listMembers: suspend (householdId: String) -> List<HouseholdMember>,
    private val renameMember: suspend (householdId: String, personId: String, name: String) -> HouseholdMember,
    private val archiveMember: suspend (householdId: String, personId: String) -> Unit,
    /** Replaces the members shown across the app with the server's list. */
    private val applyMembers: (List<HouseholdMember>) -> Unit,
    /** Puts one person's name back on screen (after a refusal, or if the server changed it). */
    private val applyName: (personId: String, name: String) -> Unit,
    private val debounceMillis: Long = 600,
) {
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var householdId: String? = null
    private val saved = mutableMapOf<String, String>()
    private val latest = mutableMapOf<String, String>()
    private val pending = mutableMapOf<String, Job>()
    private val lock = Mutex()

    private val editing: Boolean get() = pending.values.any { it.isActive }

    /** Loads the household's active members. Skips applying them if the user is mid-edit. */
    fun load(id: String) {
        householdId = id
        if (editing) return
        scope.launch {
            try {
                val members = listMembers(id)
                if (editing) return@launch
                saved.clear()
                latest.clear()
                members.forEach {
                    saved[it.id] = it.name
                    latest[it.id] = it.name
                }
                applyMembers(members)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Keep showing what we have; the next open tries again.
            }
        }
    }

    /** Call after the name has been changed on screen. The save is sent once typing pauses. */
    fun rename(personId: String, name: String) {
        latest[personId] = name
        message = null
        val id = householdId ?: return
        pending[personId]?.cancel()
        pending[personId] = scope.launch {
            delay(debounceMillis)
            lock.withLock { flush(id, personId) }
        }
    }

    private suspend fun flush(id: String, personId: String) {
        val sent = latest[personId] ?: return
        val trimmed = sent.trim()
        if (trimmed == saved[personId]) return
        validateMemberName(trimmed, emptyList())?.let {
            revert(personId, sent, it)
            return
        }
        try {
            val result = renameMember(id, personId, trimmed)
            saved[personId] = result.name
            message = null
            if (latest[personId] == sent && result.name != sent) {
                latest[personId] = result.name
                applyName(personId, result.name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            revert(personId, sent, e.apiMessage())
        }
    }

    private fun revert(personId: String, sent: String, reason: String) {
        message = reason
        if (latest[personId] != sent) return
        saved[personId]?.let {
            latest[personId] = it
            applyName(personId, it)
        }
    }

    /** Archives a person, then reloads the list. A refusal (for example, still named on a draft) sets [message]. */
    fun archive(personId: String, onArchived: () -> Unit) {
        val id = householdId ?: return
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                archiveMember(id, personId)
                try {
                    applyMembers(listMembers(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The archive worked; the list refreshes the next time it loads.
                }
                onArchived()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = e.apiMessage()
            } finally {
                busy = false
            }
        }
    }
}