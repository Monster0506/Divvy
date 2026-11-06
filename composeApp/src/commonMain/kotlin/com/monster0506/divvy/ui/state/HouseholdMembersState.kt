package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.SettingsResult
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.platform.normalizeVenmoUsername
import com.monster0506.divvy.platform.venmoUsernameProblem

/**
 * The household's members on Account: who is active and who is archived, each person's Venmo username, and
 * adding someone. Renaming and archiving a person happen on their own screen.
 */
class HouseholdMembersState(
    /** Every member, archived ones included. */
    private val load: suspend () -> SettingsResult<List<HouseholdMember>>,
    private val create: suspend (name: String) -> CreatePersonResult,
    /** Saves a Venmo username ("" removes it) and returns it as stored. */
    private val setVenmo: suspend (personId: String, username: String) -> SettingsResult<String?> =
        { _, _ -> SettingsResult.Failure("Not connected") },
) {
    var members by mutableStateOf<List<HouseholdMember>>(emptyList())
        private set
    var archivedMembers by mutableStateOf<List<HouseholdMember>>(emptyList())
        private set
    var memberDraft by mutableStateOf("")
        private set
    var memberError by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Why loading or adding failed. */
    var error by mutableStateOf<String?>(null)
        private set
    var showArchived by mutableStateOf(false)
        private set
    /** The member whose Venmo username is open for editing. */
    var editingVenmo by mutableStateOf<HouseholdMember?>(null)
        private set
    var venmoDraft by mutableStateOf("")
        private set
    private var serverVenmoError by mutableStateOf<String?>(null)

    val venmoMessage: String? get() = serverVenmoError ?: venmoUsernameProblem(venmoDraft)

    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            when (val result = load()) {
                is SettingsResult.Ok -> {
                    members = result.value.filter { !it.archived }
                    archivedMembers = result.value.filter { it.archived }
                    loaded = true
                }
                is SettingsResult.Invalid -> error = result.message
                is SettingsResult.Failure -> error = result.message
            }
        } finally {
            loading = false
        }
    }

    fun onMemberDraftChange(value: String) {
        memberDraft = value
        memberError = null
    }

    suspend fun addMember(): Boolean {
        val message = validateMemberName(memberDraft, members.map { it.name })
        if (message != null) {
            memberError = message
            return false
        }
        if (busy) return false
        val trimmed = memberDraft.trim()
        busy = true
        error = null
        memberError = null
        try {
            return when (val result = create(trimmed)) {
                is CreatePersonResult.Created -> {
                    members = members + HouseholdMember(result.id, trimmed)
                    memberDraft = ""
                    true
                }
                is CreatePersonResult.Invalid -> {
                    memberError = result.message
                    false
                }
                is CreatePersonResult.Failure -> {
                    error = result.message
                    false
                }
            }
        } finally {
            busy = false
        }
    }

    fun toggleArchived() {
        showArchived = !showArchived
    }

    fun editVenmo(member: HouseholdMember) {
        if (busy || member !in members) return
        editingVenmo = member
        venmoDraft = member.venmoUsername.orEmpty()
        serverVenmoError = null
    }

    fun onVenmoDraftChange(value: String) {
        venmoDraft = value
        serverVenmoError = null
    }

    fun cancelVenmo() {
        editingVenmo = null
    }

    /** Saves the draft for the member being edited; a blank draft removes their username. */
    suspend fun saveVenmo(): Boolean {
        val target = editingVenmo ?: return false
        if (busy || venmoUsernameProblem(venmoDraft) != null) return false
        busy = true
        error = null
        serverVenmoError = null
        try {
            return when (val result = setVenmo(target.id, normalizeVenmoUsername(venmoDraft))) {
                is SettingsResult.Ok -> {
                    members = members.map { if (it.id == target.id) it.copy(venmoUsername = result.value) else it }
                    editingVenmo = null
                    true
                }
                is SettingsResult.Invalid -> {
                    serverVenmoError = result.message
                    false
                }
                is SettingsResult.Failure -> {
                    serverVenmoError = result.message
                    false
                }
            }
        } finally {
            busy = false
        }
    }
}
