package com.monster0506.divvy.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.ui.state.validateHouseholdName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the household name on screen in step with the server: loads it, and saves edits after a short
 * pause. A refused or failed save puts the last saved name back and sets [message].
 */
class HouseholdNameController(
    private val scope: CoroutineScope,
    private val fetch: suspend (householdId: String) -> String,
    private val rename: suspend (householdId: String, name: String) -> String,
    /** Puts a name into the on-screen state (the Account field and every header that shows it). */
    private val setLocal: (String) -> Unit,
    private val debounceMillis: Long = 600,
) {
    var message by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    private var householdId: String? = null
    private var savedName: String? = null
    private var latest: String = ""
    private var debounceJob: Job? = null
    private var saveJob: Job? = null
    private var dirty = false

    private val busy: Boolean get() = debounceJob?.isActive == true || saveJob?.isActive == true

    /** Loads the saved name. Skips applying it if the user has edited since the call started. */
    fun open(id: String) {
        householdId = id
        if (busy) return
        scope.launch {
            try {
                val name = fetch(id)
                if (busy) return@launch
                savedName = name
                latest = name
                setLocal(name)
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Could not load the household name from the server."
            }
        }
    }

    /** Call on every keystroke. The save is sent once typing pauses. */
    fun edit(value: String) {
        latest = value
        setLocal(value)
        message = null
        val id = householdId ?: return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMillis)
            requestSave(id)
        }
    }

    private fun requestSave(id: String) {
        if (saveJob?.isActive == true) {
            dirty = true
            return
        }
        saveJob = scope.launch {
            do {
                dirty = false
                flush(id)
            } while (dirty)
        }
    }

    private suspend fun flush(id: String) {
        val sent = latest
        val trimmed = sent.trim()
        if (trimmed == savedName) return
        validateHouseholdName(trimmed)?.let {
            revert(sent, it)
            return
        }
        saving = true
        try {
            val saved = rename(id, trimmed)
            savedName = saved
            message = null
            if (latest == sent && saved != sent) {
                latest = saved
                setLocal(saved)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            revert(sent, e.renameMessage())
        } finally {
            saving = false
        }
    }

    /** Puts the last saved name back, unless the user has typed something newer since. */
    private fun revert(sent: String, reason: String) {
        message = reason
        if (latest != sent) return
        savedName?.let {
            latest = it
            setLocal(it)
        }
    }
}

private fun Throwable.renameMessage(): String = when (this) {
    is ApiException -> when (val reason = failure) {
        ApiFailure.Offline -> "Could not reach the server, so the name was not changed."
        ApiFailure.Unauthorised -> "The server rejected the API key, so the name was not changed."
        is ApiFailure.Validation -> reason.error.message.ifBlank { "The server did not accept that name." }
        is ApiFailure.ServerError -> "The server could not save the name. Please try again."
    }
    else -> "Could not change the household name."
}