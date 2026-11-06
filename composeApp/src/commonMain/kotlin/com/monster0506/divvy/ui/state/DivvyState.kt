package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.Settings
import com.monster0506.divvy.ui.model.Suggestion
import com.monster0506.divvy.ui.model.initialsFor

/**
 * What the app knows about the household on this device: its members as the server lists them (with the photo,
 * icon and color chosen here), who this device is, the household's name and its auto-assign settings. Everything
 * starts empty and is filled in from the server; nothing stands in for it while it loads.
 */
class DivvyState {
    /** The household's members. Every other person shown in the UI is resolved against this list by id. */
    var people by mutableStateOf(emptyList<Person>())
        private set

    /** The member this device acts as, or null until the person has said who they are. */
    var meId by mutableStateOf<String?>(null)
        private set
    val me: Person? get() = meId?.let(::person)

    /** Selects the household member represented by this device until sign-in supplies a durable identity. */
    fun setMe(id: String) {
        meId = id
    }

    fun forgetMe() {
        meId = null
    }

    /** The household's auto-assign settings, or null until they have loaded. */
    var settings by mutableStateOf<Settings?>(null)

    /** The household's name, or empty until it has loaded. */
    var householdName by mutableStateOf("")

    /** Whether the review screen shows the lines that were assigned without asking. */
    var autoExpanded by mutableStateOf(false)
        private set

    fun toggleAuto() {
        autoExpanded = !autoExpanded
    }

    /** "You may also need" suggestions; empty until running-low predictions (#137) reach the app. */
    var suggestions by mutableStateOf(emptyList<Suggestion>())
        private set

    /** Hides suggestion [index] and returns its name to put on the list. */
    fun addSuggestion(index: Int): String? {
        val s = suggestions.getOrNull(index) ?: return null
        suggestions = suggestions.mapIndexed { i, x -> if (i == index) x.copy(added = true) else x }
        return s.name
    }

    fun person(id: String): Person? = people.find { it.id == id }

    fun canRemove(id: String) = id != meId && people.size > MIN_MEMBERS

    fun updatePerson(updated: Person) {
        people = people.map { if (it.id == updated.id) updated else it }
    }

    /** Shows the household's members as the server lists them, keeping the photo, icon and color chosen here by id. */
    fun loadMembers(members: List<HouseholdMember>) {
        if (members.isEmpty()) return
        val before = people.associateBy { it.id }
        people = members.map { m ->
            val old = before[m.id]
            val customInitials = old != null && old.initials != initialsFor(old.name)
            Person(
                id = m.id,
                name = m.name,
                initials = if (customInitials) old!!.initials else initialsFor(m.name),
                color = old?.color
                    ?: AvatarColor.entries[(m.id.hashCode() and Int.MAX_VALUE) % AvatarColor.entries.size],
                emoji = old?.emoji,
                photo = old?.photo,
            )
        }
        if (meId != null && members.none { it.id == meId }) meId = null
    }

    /** Renames a person, keeping their short name in step until it has been customised. */
    fun renamePerson(id: String, name: String) {
        val person = person(id) ?: return
        val auto = person.initials == initialsFor(person.name)
        updatePerson(person.copy(name = name, initials = if (auto) initialsFor(name) else person.initials))
    }
}
