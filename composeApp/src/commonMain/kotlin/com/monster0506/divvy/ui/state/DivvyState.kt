package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.HouseholdMember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.domain.DebouncedSaver
import com.monster0506.divvy.domain.editLine
import com.monster0506.divvy.domain.nextLineId
import com.monster0506.divvy.domain.shares as splitShares
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.HistoryEntry
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.model.Settings
import com.monster0506.divvy.ui.model.Transfer
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.preview.SampleData
import kotlin.math.roundToInt

/** In-memory stand-in for the repository layer (#6) so the screens can be driven live. */
class DivvyState(private val ownerSaver: DebouncedSaver<Int, Set<String>>? = null, ) {
    /** The household's members. Every other person shown in the UI is resolved against this list by id. */
    var people by mutableStateOf(SampleData.people)
        private set

    /** Stand-in for the signed-in user until account sign-in (#143) lands. */
    var meId by mutableStateOf(SampleData.andrew.id)
        private set
    val me: Person get() = people.find { it.id == meId } ?: people.firstOrNull() ?: SampleData.andrew

    /** Selects the household member represented by this device until sign-in supplies a durable identity. */
    fun setMe(id: String) {
        meId = id
    }

    var settings by mutableStateOf(Settings())

    // Lines the user has assigned or confirmed by hand; the rest follow the auto-assign settings.
    private var reviewed by mutableStateOf(emptySet<Int>())
    private var pendingReview by mutableStateOf(emptySet<Int>())
    private var rawDraft by mutableStateOf(SampleData.draft)
    val draft: ReceiptDraft
        get() = rawDraft.copy(
            payer = resolve(rawDraft.payer),
            people = people,
            lines = rawDraft.lines.map { line ->
                val auto = settings.autoAssign && line.confidence >= settings.autoAssignThreshold
                line.copy(confirmed = line.id !in pendingReview && (line.id in reviewed || auto))
            },
        )

    /** The draft as edited so far with every line locked in, which is what the split is computed from. */
    val resolvedDraft: ReceiptDraft
        get() = draft.copy(lines = draft.lines.map { it.copy(confirmed = true) })
    val shares: List<Pair<Person, Int>>
        get() = resolvedDraft.splitShares()

    var autoExpanded by mutableStateOf(false)
        private set
    private var paidFrom by mutableStateOf(emptySet<String>())

    /** Everyone but the payer owes the payer their share of the edited receipt. */
    val transfers: List<Transfer>
        get() {
            val payer = draft.payer
            return shares.filter { (p, cents) -> p.id != payer.id && cents > 0 }
                .map { (p, cents) -> Transfer(p, payer, cents, paid = p.id in paidFrom) }
        }
    /** "You may also need" stand-ins until running-low predictions (#137) reach the app. */
    var suggestions by mutableStateOf(SampleData.suggestions)
        private set
    private var rawHistory by mutableStateOf(SampleData.history)
    val history: List<HistoryEntry>
        get() = rawHistory.mapIndexed { i, e ->
            // The first entry is the receipt being reviewed, so its total follows the edits.
            e.copy(payer = resolve(e.payer), totalCents = if (i == 0) draft.totalCents else e.totalCents)
        }
    var householdName by mutableStateOf("Apartment 4B")
    var newMember by mutableStateOf("")
    private var nextId = 0

    val balances: List<Balance>
        get() {
            val all = transfers
            val paid = all.filter { it.paid }
            return draft.people.map { person ->
                val owed = all.sumOf {
                    when (person.id) {
                        it.to.id -> it.amountCents
                        it.from.id -> -it.amountCents
                        else -> 0
                    }
                }
                val settled = paid.sumOf {
                    when (person.id) {
                        it.from.id -> it.amountCents
                        it.to.id -> -it.amountCents
                        else -> 0
                    }
                }
                Balance(person, owed + settled)
            }.sortedByDescending { it.netCents }
        }

    /** Current details for [p], or [p] itself if they're no longer in the household. */
    fun resolve(p: Person): Person = people.find { it.id == p.id } ?: p

    fun person(id: String): Person? = people.find { it.id == id }

    fun canRemove(id: String) = id != meId && people.size > 2

    fun toggleOwner(lineId: Int, personId: String) {
        rawDraft = rawDraft.copy(
            lines = rawDraft.lines.map {
                if (it.id != lineId) it
                else it.copy(owners = if (personId in it.owners) it.owners - personId else it.owners + personId)
            },
        )
        reviewed = reviewed - lineId
        pendingReview = pendingReview + lineId
    }

    fun confirmLine(lineId: Int) {
        val owners = rawDraft.lines.find { it.id == lineId }?.owners ?: return
        if (owners.isEmpty()) return
        reviewed = reviewed + lineId
        pendingReview = pendingReview - lineId
        ownerSaver?.submit(lineId, owners)
    }

    private var lastDeleted by mutableStateOf<Pair<Int, ReceiptLine>?>(null)
    val canUndoDelete get() = lastDeleted != null

    fun editLine(lineId: Int, name: String, quantity: Double, eachCents: Int) {
        rawDraft = rawDraft.copy(lines = rawDraft.lines.editLine(lineId, name, quantity, eachCents))
    }

    fun addLine(name: String, quantity: Double, eachCents: Int) {
        val line = ReceiptLine(
            rawDraft.lines.nextLineId(), name, (eachCents * quantity).roundToInt(), emptySet(),
            confidence = 0f, confirmed = false, quantity = quantity, unitCents = eachCents,
        )
        rawDraft = rawDraft.copy(lines = rawDraft.lines + line)
    }

    fun deleteLine(lineId: Int) {
        val index = rawDraft.lines.indexOfFirst { it.id == lineId }
        if (index < 0) return
        lastDeleted = index to rawDraft.lines[index]
        rawDraft = rawDraft.copy(lines = rawDraft.lines.filterNot { it.id == lineId })
    }

    fun undoDelete() {
        val (index, line) = lastDeleted ?: return
        val lines = rawDraft.lines.toMutableList().apply { add(index.coerceAtMost(size), line) }
        rawDraft = rawDraft.copy(lines = lines)
        lastDeleted = null
    }

    fun toggleAuto() {
        autoExpanded = !autoExpanded
    }

    fun confirmSplit() {
        rawHistory = rawHistory.mapIndexed { i, e -> if (i == 0) e.copy(status = ReceiptStatus.Confirmed) else e }
    }

    fun togglePaid(index: Int) {
        val id = transfers.getOrNull(index)?.from?.id ?: return
        paidFrom = if (id in paidFrom) paidFrom - id else paidFrom + id
    }

    /** Hides suggestion [index] and returns its name to put on the list. */
    fun addSuggestion(index: Int): String? {
        val s = suggestions.getOrNull(index) ?: return null
        suggestions = suggestions.mapIndexed { i, x -> if (i == index) x.copy(added = true) else x }
        return s.name
    }

    /** Adds a member and returns their id. */
    fun addPerson(name: String): String {
        val id = "new${nextId++}"
        val color = AvatarColor.entries[(people.size) % AvatarColor.entries.size]
        people = people + Person(id, name, initialsFor(name), color)
        return id
    }

    fun addMember() {
        val name = newMember.trim()
        if (name.isNotEmpty()) addPerson(name)
        newMember = ""
    }

    fun updatePerson(updated: Person) {
        people = people.map { if (it.id == updated.id) updated else it }
    }

    /** Replaces the sample people with the household's real members, keeping local photo/emoji/color by id. */
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
        // Until sign-in exists (#143), the first member stands in for "me".
        if (members.none { it.id == meId }) meId = members.first().id
    }

    /** Renames a person, keeping their short name in step until it has been customised. */
    fun renamePerson(id: String, name: String) {
        val person = person(id) ?: return
        val auto = person.initials == initialsFor(person.name)
        updatePerson(person.copy(name = name, initials = if (auto) initialsFor(name) else person.initials))
    }

    fun removePerson(id: String) {
        if (canRemove(id)) people = people.filterNot { it.id == id }
    }
}
