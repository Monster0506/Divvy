package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.api.userMessage
import kotlinx.coroutines.CancellationException

class JoinHouseholdState(
    private val householdId: String,
    private val loadMembers: suspend (String) -> List<HouseholdMember>,
) {
    var members by mutableStateOf<List<HouseholdMember>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val canContinue get() = !loading && members.any { it.id == selectedId }

    fun select(personId: String) {
        if (members.any { it.id == personId }) selectedId = personId
    }

    suspend fun load() {
        loading = true
        message = null
        try {
            members = loadMembers(householdId).filter { !it.archived }
            selectedId = selectedId?.takeIf { id -> members.any { it.id == id } }
            if (members.isEmpty()) message = "This household has no members yet. Ask its creator to finish setup, then try again."
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            message = e.failure.userMessage()
        } catch (_: Exception) {
            message = "Could not load this household's members. Check your connection and try again."
        } finally {
            loading = false
        }
    }
}
