package com.monster0506.divvy.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.model.ShoppingItem
import com.monster0506.divvy.ui.preview.SampleData

/** In-memory stand-in for the repository layer (#6) so the screens can be driven live. */
class DivvyState {
    var draft by mutableStateOf(SampleData.draft)
        private set
    var autoExpanded by mutableStateOf(false)
        private set
    var transfers by mutableStateOf(SampleData.transfers.map { it.copy(paid = false) })
        private set
    var shopping by mutableStateOf(SampleData.shopping)
        private set
    var suggestions by mutableStateOf(SampleData.suggestions)
        private set
    var history by mutableStateOf(SampleData.history)
        private set
    var householdName by mutableStateOf("Apartment 4B")
    var members by mutableStateOf(SampleData.people.map { it.name })
        private set
    var newMember by mutableStateOf("")

    val balances: List<Balance>
        get() = SampleData.balances.map { b ->
            val paid = transfers.filter { it.paid }
            val delta = paid.sumOf {
                when (b.person.id) {
                    it.from.id -> it.amountCents
                    it.to.id -> -it.amountCents
                    else -> 0
                }
            }
            b.copy(netCents = b.netCents + delta)
        }

    fun toggleOwner(lineId: Int, personId: String) {
        draft = draft.copy(
            lines = draft.lines.map {
                if (it.id != lineId) it
                else it.copy(
                    owners = if (personId in it.owners) it.owners - personId else it.owners + personId,
                    confirmed = true,
                )
            },
        )
    }

    fun confirmLine(lineId: Int) {
        draft = draft.copy(lines = draft.lines.map { if (it.id == lineId) it.copy(confirmed = true) else it })
    }

    fun toggleAuto() {
        autoExpanded = !autoExpanded
    }

    fun confirmSplit() {
        history = history.mapIndexed { i, e -> if (i == 0) e.copy(status = ReceiptStatus.Confirmed) else e }
    }

    fun togglePaid(index: Int) {
        transfers = transfers.mapIndexed { i, t -> if (i == index) t.copy(paid = !t.paid) else t }
    }

    fun toggleShopping(index: Int) {
        shopping = shopping.mapIndexed { i, s -> if (i == index) s.copy(done = !s.done) else s }
    }

    fun addSuggestion(index: Int) {
        val s = suggestions[index]
        suggestions = suggestions.mapIndexed { i, x -> if (i == index) x.copy(added = true) else x }
        shopping = shopping + ShoppingItem(s.name, done = false)
    }

    fun addMember() {
        val name = newMember.trim()
        if (name.isNotEmpty()) members = members + name
        newMember = ""
    }

    fun removeMember(index: Int) {
        members = members.filterIndexed { i, _ -> i != index }
    }
}
