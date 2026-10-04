package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.CreateHouseholdResult

const val HOUSEHOLD_NAME_MAX = 80

/** Mirrors the server rule: trimmed, 1..80 characters. Returns a message, or null when valid. */
fun validateHouseholdName(raw: String): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> "Enter a household name"
        name.length > HOUSEHOLD_NAME_MAX -> "Keep it to $HOUSEHOLD_NAME_MAX characters or fewer"
        else -> null
    }
}

class CreateHouseholdState(
    private val create: suspend (name: String) -> CreateHouseholdResult,
) {
    var name by mutableStateOf("")
        private set
    var submitting by mutableStateOf(false)
        private set
    /** Network/server problem, shown above the button. */
    var error by mutableStateOf<String?>(null)
        private set
    private var touched by mutableStateOf(false)
    private var serverFieldError by mutableStateOf<String?>(null)

    /** Inline message under the text field. */
    val fieldMessage: String? get() = serverFieldError ?: if (touched) validateHouseholdName(name) else null
    val canSubmit: Boolean get() = !submitting && validateHouseholdName(name) == null

    fun onNameChange(value: String) {
        name = value
        touched = true
        serverFieldError = null
        error = null
    }

    /** Returns the new household id on success, or null (with [fieldMessage] / [error] set). */
    suspend fun submit(): String? {
        touched = true
        if (!canSubmit) return null
        submitting = true
        error = null
        serverFieldError = null
        try {
            return when (val result = create(name.trim())) {
                is CreateHouseholdResult.Created -> result.id
                is CreateHouseholdResult.Invalid -> { serverFieldError = result.message; null }
                is CreateHouseholdResult.Failure -> { error = result.message; null }
            }
        } finally {
            submitting = false
        }
    }
}