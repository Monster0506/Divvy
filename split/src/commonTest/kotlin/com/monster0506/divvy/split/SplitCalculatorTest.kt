package com.monster0506.divvy.split

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SplitCalculatorTest {

    private fun item(id: String, cents: Long, vararg owners: Pair<String, Int>) =
        SplitItem(id, cents, owners.map { Owner(it.first, it.second) })

    private fun calc(vararg items: SplitItem) = SplitCalculator.calculate(SplitInput(items.toList())).shares

    @Test
    fun singleOwnerPaysWholeItem() {
        assertEquals(mapOf("a" to 500L), calc(item("i1", 500, "a" to 1)))
    }

    @Test
    fun allOwnersSplitEvenly() {
        val shares = calc(item("i1", 900, "a" to 1, "b" to 1, "c" to 1))
        assertEquals(mapOf("a" to 300L, "b" to 300L, "c" to 300L), shares)
    }

    @Test
    fun subsetOfHouseholdOnlyChargesOwners() {
        val shares = calc(item("i1", 1000, "a" to 1, "c" to 1))
        assertEquals(mapOf("a" to 500L, "c" to 500L), shares)
    }

    @Test
    fun weightsAreRespected() {
        val shares = calc(item("i1", 1000, "a" to 3, "b" to 1))
        assertEquals(mapOf("a" to 750L, "b" to 250L), shares)
    }

    @Test
    fun oddCentGoesToLowestIdOnTie() {
        val shares = calc(item("i1", 100, "b" to 1, "a" to 1, "c" to 1))
        assertEquals(mapOf("a" to 34L, "b" to 33L, "c" to 33L), shares)
    }

    @Test
    fun largestRemainderGetsLeftoverCent() {
        // 10 split 1:2 -> 3.33 / 6.67, the extra cent goes to the larger remainder
        val shares = calc(item("i1", 10, "a" to 1, "b" to 2))
        assertEquals(mapOf("a" to 3L, "b" to 7L), shares)
    }

    @Test
    fun sharesSumToItemPrice() {
        val shares = calc(item("i1", 101, "a" to 2, "b" to 3, "c" to 7))
        assertEquals(101L, shares.values.sum())
    }

    @Test
    fun itemsAccumulatePerPerson() {
        val shares = calc(
            item("i1", 300, "a" to 1, "b" to 1),
            item("i2", 200, "a" to 1),
        )
        assertEquals(mapOf("a" to 350L, "b" to 150L), shares)
    }

    @Test
    fun sameInputGivesSameOutput() {
        val input = SplitInput(listOf(item("i1", 101, "c" to 1, "a" to 1, "b" to 1)))
        assertEquals(SplitCalculator.calculate(input), SplitCalculator.calculate(input))
    }

    @Test
    fun zeroPriceItemGivesZeroShares() {
        assertEquals(mapOf("a" to 0L, "b" to 0L), calc(item("i1", 0, "a" to 1, "b" to 1)))
    }

    @Test
    fun invalidItemsAreRejected() {
        assertFailsWith<IllegalArgumentException> { calc(item("i1", 100)) }
        assertFailsWith<IllegalArgumentException> { calc(item("i1", 100, "a" to 0)) }
        assertFailsWith<IllegalArgumentException> { calc(item("i1", -1, "a" to 1)) }
        assertFailsWith<IllegalArgumentException> { calc(item("i1", 100, "a" to 1, "a" to 1)) }
    }
}
