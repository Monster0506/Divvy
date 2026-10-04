package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.OwnershipSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MlSettingsTest {
    private val household = Uuid.random()
    private val tj = person(household, "TJ")
    private val receipt = Uuid.random()

    private fun guess(confidence: String) =
        item(receipt, "Eggs", listOf(tj), null, ownerSource = OwnershipSource.MODEL, confidence = BigDecimal(confidence))

    @Test
    fun `defaults are on with the 0_85 threshold`() {
        assertEquals(MlSettings(true, 0.85), MlSettings())
    }

    @Test
    fun `a guess below the threshold needs confirming and one at or above does not`() {
        val rules = MlSettings(true, 0.85)

        assertTrue(rules.needsConfirmation(guess("0.8499")))
        assertFalse(rules.needsConfirmation(guess("0.8500")))
        assertFalse(rules.needsConfirmation(guess("0.9900")))
    }

    @Test
    fun `with auto-assign off every guess needs confirming`() {
        assertTrue(MlSettings(false, 0.5).needsConfirmation(guess("0.9900")))
    }

    @Test
    fun `an item a person chose never needs confirming`() {
        val chosen = item(receipt, "Eggs", listOf(tj), null, ownerSource = OwnershipSource.USER)

        assertFalse(MlSettings(false, 1.0).needsConfirmation(chosen))
    }

    @Test
    fun `an unassigned item does not need confirming because there is nothing to confirm`() {
        assertFalse(MlSettings().needsConfirmation(item(receipt, "Eggs", emptyList(), null)))
    }

    @Test
    fun `a model owner with no recorded confidence needs confirming`() {
        val noScore = item(receipt, "Eggs", listOf(tj), null, ownerSource = OwnershipSource.MODEL, confidence = null)

        assertTrue(MlSettings().needsConfirmation(noScore))
    }

    @Test
    fun `an item shared by several guessed owners is judged by the least sure one`() {
        val sam = person(household, "Sam")
        val base = guess("0.9500")
        val shared = base.copy(owners = base.owners + base.owners.first().copy(personId = sam.id, confidence = BigDecimal("0.7000")))

        assertTrue(MlSettings().needsConfirmation(shared))
    }
}
