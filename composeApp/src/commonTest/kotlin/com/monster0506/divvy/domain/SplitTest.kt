package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.preview.SampleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitTest {
    private fun draft() = SampleData.draft.copy(lines = SampleData.draft.lines.map { it.copy(confirmed = true) })

    @Test
    fun sharesAddUpToTheTotal() {
        val d = draft()
        assertEquals(d.totalCents, d.shares().sumOf { it.second })
    }

    @Test
    fun editingAPriceMovesOnlyItsOwnersShares() {
        val before = draft().shares().toMap()
        val d = draft()
        val pizza = d.lines.first { it.name == "Frozen Pizza" } // owned by Andrew alone
        val after = d.copy(lines = d.lines.editLine(pizza.id, pizza.name, 1.0, pizza.priceCents + 10_000)).shares()
        assertEquals(d.totalCents + 10_000, after.sumOf { it.second })
        val andrewBefore = before.getValue(SampleData.andrew)
        val andrewAfter = after.toMap().getValue(SampleData.andrew)
        // Andrew absorbs the extra $100 (plus his proportional share of tax is already included in the total).
        assertTrue(andrewAfter - andrewBefore >= 10_000)
    }

    @Test
    fun unownedLinesAreSplitEvenly() {
        val d = ReceiptDraft("s", "d", SampleData.tj, SampleData.people, emptyList(), 0)
            .copy(lines = listOf(com.monster0506.divvy.ui.model.ReceiptLine(1, "x", 1001, emptySet(), 0f, false)))
        assertEquals(listOf(251, 250, 250, 250), d.shares().map { it.second })
    }
}
