package com.monster0506.divvy.ui.preview

import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.HistoryEntry
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.model.ShoppingItem
import com.monster0506.divvy.ui.model.Suggestion

object SampleData {
    val sumedh = Person("su", "Sumedh", "Su", AvatarColor.Blue)
    val savar = Person("sv", "Savar", "Sv", AvatarColor.Purple)
    val andrew = Person("an", "Andrew", "An", AvatarColor.Green)
    val tj = Person("tj", "TJ", "TJ", AvatarColor.Orange)
    val people = listOf(sumedh, savar, andrew, tj)
    private val everyone = people.map { it.id }.toSet()

    private fun line(id: Int, name: String, cents: Int, owners: Set<String>, conf: Float) =
        ReceiptLine(id, name, cents, owners, conf, confirmed = conf >= 0.85f)

    val draft = ReceiptDraft(
        store = "Fresh Market",
        date = "Sat, Oct 3",
        payer = tj,
        people = people,
        taxCents = 210,
        lines = listOf(
            line(1, "Coke Zero 12pk", 899, setOf("tj"), 0.97f),
            line(2, "Paper Towels 6ct", 1249, everyone, 0.95f),
            line(3, "Frozen Pizza", 699, setOf("an"), 0.93f),
            line(4, "Basmati Rice 10lb", 1799, everyone, 0.90f),
            line(5, "Eggs 18ct", 549, everyone, 0.89f),
            line(6, "Fresh Cilantro", 129, setOf("su", "sv"), 0.58f),
            line(7, "Serrano Peppers", 189, setOf("su", "sv"), 0.52f).copy(quantity = 3.0, unitCents = 63),
            line(8, "Sandwich Bread", 429, setOf("an", "tj"), 0.61f),
        ),
    )

    val resolvedDraft = draft.copy(lines = draft.lines.map { it.copy(confirmed = true) })

    val balances = listOf(
        Balance(tj, 3410),
        Balance(sumedh, -1240),
        Balance(savar, -985),
        Balance(andrew, -1185),
    )

    val shopping = listOf(
        ShoppingItem("sample-eggs", "Eggs", false, quantity = "1 dozen", addedBy = "Andrew", addedAt = "2026-09-28T15:00:00Z"),
        ShoppingItem("sample-soap", "Dish soap", false, notes = "The lemon one", addedBy = "TJ", addedAt = "2026-09-29T09:30:00Z"),
        ShoppingItem("sample-oil", "Olive oil", true, addedBy = "Sumedh", addedAt = "2026-09-25T18:10:00Z"),
    )

    val suggestions = listOf(
        Suggestion("Sandwich bread", "Usually every 8 days, last bought 9 days ago"),
        Suggestion("Paper towels", "Last bought 24 days ago"),
    )

    val history = listOf(
        HistoryEntry("Fresh Market", "Oct 3", 6152, tj, ReceiptStatus.NeedsReview),
        HistoryEntry("Costco", "Sep 28", 9640, andrew, ReceiptStatus.Confirmed),
        HistoryEntry("Fresh Market", "Sep 21", 5418, tj, ReceiptStatus.Confirmed),
        HistoryEntry("Trader Joes", "Sep 14", 3875, sumedh, ReceiptStatus.Confirmed),
    )

    // Hand-computed for the sample receipt; real splits come from the backend (#14).
    val shares = listOf(sumedh to 1096, savar to 1096, andrew to 1876, tj to 2084)
}
