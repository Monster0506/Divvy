package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.domain.editLine
import com.monster0506.divvy.domain.mergeLines
import com.monster0506.divvy.domain.nextLineId
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.HistoryEntry
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.model.Settings
import com.monster0506.divvy.ui.model.ShoppingItem
import com.monster0506.divvy.ui.model.Transfer
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.preview.SampleData

/** In-memory stand-in for the repository layer (#6) so the screens can be driven live. */
class DivvyState {
    /** The household's members. Every other person shown in the UI is resolved against this list by id. */
    var people by mutableStateOf(SampleData.people)
        private set
    /** Stand-in for the signed-in user until account sign-in (#143) lands. */
    val meId = SampleData.andrew.id
    val me: Person get() = resolve(SampleData.andrew)

    var settings by mutableStateOf(Settings())

    // Lines the user has assigned or confirmed by hand; the rest follow the auto-assign settings.
    private var reviewed by mutableStateOf(emptySet<Int>())
    private var rawDraft by mutableStateOf(SampleData.draft)
    val draft: ReceiptDraft
        get() = rawDraft.copy(
            payer = resolve(rawDraft.payer),
            people = people,
            lines = rawDraft.lines.map { line ->
                val auto = settings.autoAssign && line.confidence >= settings.autoAssignThreshold
                line.copy(confirmed = line.id in reviewed || auto)
            },
        )
    val resolvedDraft: ReceiptDraft
        get() = SampleData.resolvedDraft.copy(payer = resolve(SampleData.resolvedDraft.payer), people = people)
    val shares: List<Pair<Person, Int>>
        get() = SampleData.shares.map { (p, cents) -> resolve(p) to cents }

    var autoExpanded by mutableStateOf(false)
        private set
    private var rawTransfers by mutableStateOf(SampleData.transfers.map { it.copy(paid = false) })
    val transfers: List<Transfer>
        get() = rawTransfers.map { it.copy(from = resolve(it.from), to = resolve(it.to)) }
    var shopping by mutableStateOf(SampleData.shopping)
        private set
    var suggestions by mutableStateOf(SampleData.suggestions)
        private set
    private var rawHistory by mutableStateOf(SampleData.history)
    val history: List<HistoryEntry>
        get() = rawHistory.map { it.copy(payer = resolve(it.payer)) }
    var householdName by mutableStateOf("Apartment 4B")
    var newMember by mutableStateOf("")
    private var nextId = 0

    val balances: List<Balance>
        get() = SampleData.balances.map { b ->
            val paid = rawTransfers.filter { it.paid }
            val delta = paid.sumOf {
                when (b.person.id) {
                    it.from.id -> it.amountCents
                    it.to.id -> -it.amountCents
                    else -> 0
                }
            }
            b.copy(person = resolve(b.person), netCents = b.netCents + delta)
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
        reviewed = reviewed + lineId
    }

    fun confirmLine(lineId: Int) {
        reviewed = reviewed + lineId
    }

    private var lastDeleted by mutableStateOf<Pair<Int, ReceiptLine>?>(null)
    val canUndoDelete get() = lastDeleted != null

    fun editLine(lineId: Int, name: String, priceCents: Int) {
        rawDraft = rawDraft.copy(lines = rawDraft.lines.editLine(lineId, name, priceCents))
    }

    fun addLine(name: String, priceCents: Int) {
        val line = ReceiptLine(rawDraft.lines.nextLineId(), name, priceCents, emptySet(), confidence = 0f, confirmed = false)
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

    fun mergeLines(ids: Set<Int>) {
        rawDraft = rawDraft.copy(lines = rawDraft.lines.mergeLines(ids))
    }

    fun toggleAuto() {
        autoExpanded = !autoExpanded
    }

    fun confirmSplit() {
        rawHistory = rawHistory.mapIndexed { i, e -> if (i == 0) e.copy(status = ReceiptStatus.Confirmed) else e }
    }

    fun togglePaid(index: Int) {
        rawTransfers = rawTransfers.mapIndexed { i, t -> if (i == index) t.copy(paid = !t.paid) else t }
    }

    fun toggleShopping(index: Int) {
        shopping = shopping.mapIndexed { i, s -> if (i == index) s.copy(done = !s.done) else s }
    }

    fun addSuggestion(index: Int) {
        val s = suggestions[index]
        suggestions = suggestions.mapIndexed { i, x -> if (i == index) x.copy(added = true) else x }
        shopping = shopping + ShoppingItem(s.name, done = false)
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

    fun removePerson(id: String) {
        if (canRemove(id)) people = people.filterNot { it.id == id }
    }
}
