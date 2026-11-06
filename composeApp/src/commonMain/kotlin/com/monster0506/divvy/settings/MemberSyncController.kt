package com.monster0506.divvy.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.api.apiMessage
import com.monster0506.divvy.data.dto.AppearanceRequest
import com.monster0506.divvy.ui.model.Appearance
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.state.validateMemberName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the household's members in step with the server: loads them, saves renames and avatar changes after a
 * short pause, saves photos, and archives people. A refused change puts the last saved value back and sets [message].
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
    /** Saves avatar choices; see [AppearanceRequest]. */
    private val saveAppearance: suspend (householdId: String, personId: String, change: AppearanceRequest) -> HouseholdMember,
    /** Puts one person's avatar choices back on screen (after a refusal, or if the server changed them). */
    private val applyAppearance: (personId: String, appearance: Appearance) -> Unit,
    /** Replaces a person's photo with JPEG bytes, or removes it when they are null. */
    private val savePhoto: suspend (householdId: String, personId: String, jpeg: ByteArray?) -> HouseholdMember,
    private val debounceMillis: Long = 600,
) {
    var message by mutableStateOf<String?>(null)
        private set
    /** Why the last photo, short name, icon or color change was refused. */
    var lookMessage by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var householdId: String? = null
    private val saved = mutableMapOf<String, String>()
    private val latest = mutableMapOf<String, String>()
    private val savedLook = mutableMapOf<String, Appearance>()
    private val latestLook = mutableMapOf<String, Appearance>()
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
                savedLook.clear()
                latestLook.clear()
                members.forEach {
                    saved[it.id] = it.name
                    latest[it.id] = it.name
                    savedLook[it.id] = it.appearance()
                    latestLook[it.id] = it.appearance()
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

    /** Call after a person's short name, icon or color has changed on screen. The save is sent once typing pauses. */
    fun restyle(personId: String, appearance: Appearance) {
        latestLook[personId] = appearance
        lookMessage = null
        val id = householdId ?: return
        val key = "look:$personId"
        pending[key]?.cancel()
        pending[key] = scope.launch {
            delay(debounceMillis)
            lock.withLock { flushLook(id, personId) }
        }
    }

    private suspend fun flushLook(id: String, personId: String) {
        val sent = latestLook[personId] ?: return
        val wanted = sent.normalized()
        val before = savedLook[personId] ?: Appearance()
        if (wanted == before) return
        // Only what changed is sent; an empty string clears it.
        fun change(new: String?, old: String?) = if (new == old) null else new.orEmpty()
        val request = AppearanceRequest(
            shortName = change(wanted.shortName, before.shortName),
            emoji = change(wanted.emoji, before.emoji),
            color = change(wanted.color?.key, before.color?.key),
        )
        try {
            val result = saveAppearance(id, personId, request).appearance()
            savedLook[personId] = result
            lookMessage = null
            if (latestLook[personId] == sent && result != sent) {
                latestLook[personId] = result
                applyAppearance(personId, result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lookMessage = e.apiMessage()
            if (latestLook[personId] != sent) return
            latestLook[personId] = before
            applyAppearance(personId, before)
        }
    }

    /**
     * Saves a new photo for [personId] (JPEG bytes), or removes theirs when [jpeg] is null. Saves run one at a time,
     * in order. [onSaved] gets the person as the server now has them; on a refusal [lookMessage] is set and
     * [onRefused] should put the saved photo back.
     */
    fun setPhoto(personId: String, jpeg: ByteArray?, onSaved: (HouseholdMember) -> Unit, onRefused: () -> Unit) {
        val id = householdId ?: return
        lookMessage = null
        scope.launch {
            lock.withLock {
                try {
                    onSaved(savePhoto(id, personId, jpeg))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lookMessage = e.apiMessage()
                    onRefused()
                }
            }
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

/** The avatar choices the server has for this member. */
internal fun HouseholdMember.appearance() = Appearance(shortName, emoji, AvatarColor.fromKey(color))

/** Blank short names and icons mean "not chosen". */
private fun Appearance.normalized() =
    copy(shortName = shortName?.trim()?.takeIf { it.isNotEmpty() }, emoji = emoji?.takeIf { it.isNotBlank() })