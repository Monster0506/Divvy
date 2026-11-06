package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.ui.model.Appearance
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.Settings
import com.monster0506.divvy.ui.model.Suggestion
import com.monster0506.divvy.ui.model.defaultAvatarColor
import com.monster0506.divvy.ui.model.initialsFor

/**
 * What the app knows about the household on this device: its members as the server lists them (with their saved
 * short names, icons, colors and photos), who this device is, the household's name and its auto-assign settings.
 * Everything starts empty and is filled in from the server; nothing stands in for it while it loads.
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

    /** The `photoUpdatedAt` the server lists for each member who has a photo. */
    private val listedPhotos = mutableMapOf<String, String>()
    /** The version of the photo each member's [Person.photo] shows. */
    private val shownPhotos = mutableMapOf<String, String>()
    /** Photo versions being downloaded, so each is fetched once. */
    private val fetchingPhotos = mutableMapOf<String, String>()

    /**
     * Shows the household's members with the avatar choices saved on the server. A photo already shown stays until
     * the server no longer lists one or a newer version has downloaded; [takePhotosToLoad] says what to download.
     */
    fun loadMembers(members: List<HouseholdMember>) {
        if (members.isEmpty()) return
        val before = people.associateBy { it.id }
        listedPhotos.clear()
        members.forEach { m -> m.photoUpdatedAt?.let { listedPhotos[m.id] = it } }
        shownPhotos.keys.retainAll { shownPhotos[it] == listedPhotos[it] }
        people = members.map { m ->
            Person(
                id = m.id,
                name = m.name,
                initials = m.shortName ?: initialsFor(m.name),
                color = AvatarColor.fromKey(m.color) ?: defaultAvatarColor(m.id),
                emoji = m.emoji,
                photo = before[m.id]?.photo?.takeIf { m.photoUpdatedAt != null },
            )
        }
        if (meId != null && members.none { it.id == meId }) meId = null
    }

    /** Members whose listed photo isn't shown or being fetched yet, with its version. They count as being fetched now. */
    fun takePhotosToLoad(): Map<String, String> {
        val wanted = listedPhotos.filter { (id, version) -> shownPhotos[id] != version && fetchingPhotos[id] != version }
        fetchingPhotos.putAll(wanted)
        return wanted
    }

    /** Shows a downloaded [photo], unless the server has listed a newer one since. */
    fun showPhoto(id: String, version: String, photo: ImageBitmap?) {
        if (fetchingPhotos[id] == version) fetchingPhotos.remove(id)
        if (photo == null || listedPhotos[id] != version) return
        shownPhotos[id] = version
        person(id)?.let { updatePerson(it.copy(photo = photo)) }
    }

    /** Shows a photo picked on this phone, or none, before the server has it. */
    fun previewPhoto(id: String, photo: ImageBitmap?) {
        shownPhotos.remove(id)
        person(id)?.let { updatePerson(it.copy(photo = photo)) }
    }

    /** Records that the server now has [photo] as version [version] (null after removing it). */
    fun photoSaved(id: String, version: String?, photo: ImageBitmap?) {
        if (version == null) {
            listedPhotos.remove(id)
            shownPhotos.remove(id)
        } else {
            listedPhotos[id] = version
            shownPhotos[id] = version
        }
        person(id)?.let { updatePerson(it.copy(photo = if (version == null) null else photo)) }
    }

    /** Shows [appearance] for [id], for example after the server refused a change. */
    fun applyAppearance(id: String, appearance: Appearance) {
        val person = person(id) ?: return
        updatePerson(
            person.copy(
                initials = appearance.shortName?.takeIf { it.isNotBlank() } ?: initialsFor(person.name),
                emoji = appearance.emoji?.takeIf { it.isNotBlank() },
                color = appearance.color ?: defaultAvatarColor(id),
            ),
        )
    }

    /** Renames a person, keeping their short name in step until it has been customised. */
    fun renamePerson(id: String, name: String) {
        val person = person(id) ?: return
        val auto = person.initials == initialsFor(person.name)
        updatePerson(person.copy(name = name, initials = if (auto) initialsFor(name) else person.initials))
    }
}
