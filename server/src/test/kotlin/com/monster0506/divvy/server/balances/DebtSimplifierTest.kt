package com.monster0506.divvy.server.balances

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DebtSimplifierTest {
    @Test
    fun emptyAndZeroBalancesNeedNoTransfers() {
        assertEquals(emptyList(), DebtSimplifier.simplify(emptyMap()))
        assertEquals(emptyList(), DebtSimplifier.simplify(mapOf("a" to 0L, "b" to 0L)))
    }

    @Test
    fun singleDebtIsOneTransfer() {
        val result = DebtSimplifier.simplify(mapOf("a" to 500L, "b" to -500L))
        assertEquals(listOf(Transfer("b", "a", 500L)), result)
    }

    @Test
    fun matchesLargestCreditorWithLargestDebtor() {
        val result = DebtSimplifier.simplify(mapOf("a" to 700L, "b" to 300L, "c" to -600L, "d" to -400L))
        assertEquals(
            listOf(Transfer("c", "a", 600L), Transfer("d", "b", 300L), Transfer("d", "a", 100L)),
            result,
        )
    }

    @Test
    fun rejectsBalancesThatDoNotSumToZero() {
        assertFailsWith<IllegalArgumentException> { DebtSimplifier.simplify(mapOf("a" to 100L, "b" to -50L)) }
    }

    @Test
    fun atMostNMinusOneTransfersAndBalancesSettle() {
        val random = Random(42)
        repeat(200) {
            val n = random.nextInt(2, 9)
            val values = MutableList(n - 1) { random.nextLong(-5000, 5000) }
            values += -values.sum()
            val balances = values.mapIndexed { i, v -> "p$i" to v }.toMap()
            val nonZero = balances.count { it.value != 0L }

            val transfers = DebtSimplifier.simplify(balances)

            assertTrue(transfers.size <= maxOf(nonZero - 1, 0))
            assertTrue(transfers.all { it.amountCents > 0 })
            val net = balances.toMutableMap()
            for (t in transfers) {
                net[t.from] = net.getValue(t.from) + t.amountCents
                net[t.to] = net.getValue(t.to) - t.amountCents
            }
            assertTrue(net.values.all { it == 0L })
        }
    }
}
