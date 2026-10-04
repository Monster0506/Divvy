package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.SettingsResult
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdDetails
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.platform.normalizeVenmoUsername
import com.monster0506.divvy.platform.venmoUsernameProblem

class HouseholdSettingsState(
    private val load: suspend () -> SettingsResult<HouseholdDetails>,
    private val rename: suspend (name: String) -> SettingsResult<Unit>,
    private val create: suspend (name: String) -> CreatePersonResult,
    private val archive: suspend (personId: String) -> SettingsResult<Unit>,
    /** Saves a Venmo username ("" removes it) and returns it as stored. */
    private val setVenmo: suspend (personId: String, username: String) -> SettingsResult<String?> =
        { _, _ -> SettingsResult.Failure("Not connected") },
) {
    /** The name as the server has it. */
    var name by mutableStateOf("")
        private set
    var nameDraft by mutableStateOf("")
        private set
    private var nameTouched by mutableStateOf(false)
    private var serverNameError by mutableStateOf<String?>(null)
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
    /** Network/server problem, shown at the top of the screen. */
    var error by mutableStateOf<String?>(null)
        private set
    /** The member waiting on the archive confirmation dialog. */
    var archiving by mutableStateOf<HouseholdMember?>(null)
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

    val nameFieldMessage: String? get() = serverNameError ?: if (nameTouched) validateHouseholdName(nameDraft) else null
    val canSaveName: Boolean
        get() = loaded && !busy && validateHouseholdName(nameDraft) == null && nameDraft.trim() != name

    /** Households keep at least [MIN_MEMBERS] active people. */
    fun canArchive(member: HouseholdMember) = !busy && member in members && members.size > MIN_MEMBERS

    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            when (val result = load()) {
                is SettingsResult.Ok -> {
                    name = result.value.name
                    nameDraft = result.value.name
                    members = result.value.members.filter { !it.archived }
                    archivedMembers = result.value.members.filter { it.archived }
                    loaded = true
                }
                is SettingsResult.Invalid -> error = result.message
                is SettingsResult.Failure -> error = result.message
            }
        } finally {
            loading = false
        }
    }

    fun onNameDraftChange(value: String) {
        nameDraft = value
        nameTouched = true
        serverNameError = null
        error = null
    }

    suspend fun saveName(): Boolean {
        nameTouched = true
        if (!canSaveName) return false
        val trimmed = nameDraft.trim()
        busy = true
        error = null
        serverNameError = null
        try {
            return when (val result = rename(trimmed)) {
                is SettingsResult.Ok -> {
                    name = trimmed
                    nameDraft = trimmed
                    true
                }
                is SettingsResult.Invalid -> {
                    serverNameError = result.message
                    false
                }
                is SettingsResult.Failure -> {
                    error = result.message
                    false
                }
            }
        } finally {
            busy = false
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

    fun requestArchive(member: HouseholdMember) {
        if (canArchive(member)) archiving = member
    }

    fun cancelArchive() {
        archiving = null
    }

    suspend fun confirmArchive(): Boolean {
        val target = archiving ?: return false
        if (busy) return false
        busy = true
        error = null
        try {
            return when (val result = archive(target.id)) {
                is SettingsResult.Ok -> {
                    members = members - target
                    archivedMembers = archivedMembers + target.copy(archived = true)
                    true
                }
                is SettingsResult.Invalid -> {
                    error = result.message
                    false
                }
                is SettingsResult.Failure -> {
                    error = result.message
                    false
                }
            }
        } finally {
            archiving = null
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