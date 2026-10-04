package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.ReceiptLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineEditsTest {
    private fun line(id: Int, price: Int, vararg owners: String, conf: Float = 0.9f) =
        ReceiptLine(id, "item$id", price, owners.toSet(), conf, confirmed = false)

    @Test
    fun parsesPrices() {
        assertEquals(1299, parsePriceCents("12.99"))
        assertEquals(1299, parsePriceCents("$12.99"))
        assertEquals(450, parsePriceCents("4.5"))
        assertEquals(400, parsePriceCents("4"))
        assertEquals(50, parsePriceCents(".5"))
        assertEquals(0, parsePriceCents("0"))
    }

    @Test
    fun rejectsBadPrices() {
        assertNull(parsePriceCents(""))
        assertNull(parsePriceCents("."))
        assertNull(parsePriceCents("-1"))
        assertNull(parsePriceCents("1.234"))
        assertNull(parsePriceCents("abc"))
    }

    @Test
    fun formatsCents() {
        assertEquals("12.99", centsToInput(1299))
        assertEquals("0.05", centsToInput(5))
        assertEquals(1299, parsePriceCents(centsToInput(1299)))
    }

    @Test
    fun editsOnlyTheTargetLine() {
        val out = listOf(line(1, 100), line(2, 200)).editLine(2, "milk", 350)
        assertEquals(listOf("item1", "milk"), out.map { it.name })
        assertEquals(listOf(100, 350), out.map { it.priceCents })
    }

    @Test
    fun mergeSumsPricesAndUnionsOwners() {
        val out = listOf(line(1, 100, "a"), line(2, 200, "b"), line(3, 300, "a", conf = 0.5f))
            .mergeLines(setOf(1, 3))
        assertEquals(listOf(1, 2), out.map { it.id })
        assertEquals(400, out[0].priceCents)
        assertEquals(setOf("a"), out[0].owners)
        assertEquals(0.5f, out[0].confidence)
    }

    @Test
    fun mergeNeedsTwoLines() {
        val lines = listOf(line(1, 100), line(2, 200))
        assertEquals(lines, lines.mergeLines(setOf(1)))
    }

    @Test
    fun nextIdIsUnused() {
        assertEquals(0, emptyList<ReceiptLine>().nextLineId())
        assertEquals(8, listOf(line(3, 1), line(7, 1)).nextLineId())
    }
}
