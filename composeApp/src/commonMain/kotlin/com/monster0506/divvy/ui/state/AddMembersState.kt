package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.CreatePersonResult

const val MEMBER_NAME_MAX = 80
const val MIN_MEMBERS = 2

/** Trimmed, 1..80 characters, and not already listed (case-insensitive). Returns a message, or null when valid. */
fun validateMemberName(raw: String, existing: List<String>): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> "Enter a name"
        name.length > MEMBER_NAME_MAX -> "Keep it to $MEMBER_NAME_MAX characters or fewer"
        existing.any { it.trim().equals(name, ignoreCase = true) } -> "$name is already in the list"
        else -> null
    }
}

/** A listed person. [id] is set once the server has created them; [error] is a server rejection of this name. */
data class MemberRow(val name: String, val id: String? = null, val error: String? = null)

class AddMembersState(
    private val create: suspend (name: String) -> CreatePersonResult,
) {
    var draft by mutableStateOf("")
        private set
    var draftError by mutableStateOf<String?>(null)
        private set
    var rows by mutableStateOf<List<MemberRow>>(emptyList())
        private set
    var meName by mutableStateOf<String?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    /** Network/server problem, shown above the button. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Counts a name typed but not yet added, so Finish works without tapping Add first. */
    private val pending: Int get() = if (draft.isNotBlank()) 1 else 0
    val canFinish: Boolean get() = !submitting && rows.size + pending >= MIN_MEMBERS && meName != null && rows.any { it.name == meName }
    val meId: String? get() = rows.firstOrNull { it.name == meName }?.id

    fun onDraftChange(value: String) {
        draft = value
        draftError = null
    }

    /** Adds the draft to the list. Returns false (with [draftError] set) when it is invalid or a duplicate. */
    fun add(): Boolean {
        val message = validateMemberName(draft, rows.map { it.name })
        if (message != null) {
            draftError = message
            return false
        }
        rows = rows + MemberRow(draft.trim())
        draft = ""
        draftError = null
        return true
    }

    /** People already created on the server stay (there is no delete call yet). */
    fun canRemove(row: MemberRow) = !submitting && row.id == null

    fun remove(row: MemberRow) {
        if (canRemove(row)) {
            rows = rows - row
            if (meName == row.name) meName = null
        }
    }

    fun setMe(name: String) {
        if (rows.any { it.name == name }) meName = name
    }

    /** Creates everyone not yet on the server. Returns true when all are created. */
    suspend fun finish(): Boolean {
        if (draft.isNotBlank() && !add()) return false
        if (rows.size < MIN_MEMBERS || meName == null || rows.none { it.name == meName } || submitting) return false
        submitting = true
        error = null
        rows = rows.map { it.copy(error = null) }
        try {
            for (row in rows.toList()) {
                if (row.id != null) continue
                when (val result = create(row.name)) {
                    is CreatePersonResult.Created -> update(row.name) { it.copy(id = result.id) }
                    is CreatePersonResult.Invalid -> update(row.name) { it.copy(error = result.message) }
                    is CreatePersonResult.Failure -> {
                        error = result.message
                        return false
                    }
                }
            }
            return rows.all { it.id != null }
        } finally {
            submitting = false
        }
    }

    private fun update(name: String, change: (MemberRow) -> MemberRow) {
        rows = rows.map { if (it.name == name) change(it) else it }
    }
}
