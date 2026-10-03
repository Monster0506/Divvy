package com.monster0506.divvy.data.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DtoSerializationTest {
    private val json = Json { encodeDefaults = true }

    private inline fun <reified T> roundTrip(value: T): T =
        json.decodeFromString(json.encodeToString(value))

    @Test
    fun household_with_people_round_trips() {
        val household = HouseholdDto(
            id = "h1",
            name = "Apartment 4B",
            people = listOf(
                PersonDto("p1", "h1", "Sumedh"),
                PersonDto("p2", "h1", "TJ", archivedAt = "2026-10-01T12:00:00Z"),
            ),
            createdAt = "2026-09-14T08:30:00Z",
        )
        assertEquals(household, roundTrip(household))
    }

    @Test
    fun archived_person_is_reported_archived() {
        assertTrue(PersonDto("p", "h", "A", archivedAt = "2026-10-01T00:00:00Z").isArchived)
        assertFalse(PersonDto("p", "h", "A").isArchived)
    }

    @Test
    fun receipt_with_multi_owner_items_round_trips() {
        val receipt = ReceiptDto(
            id = "r1",
            householdId = "h1",
            status = ReceiptStatusDto.NeedsReview,
            store = "Fresh Market",
            purchasedAt = "2026-10-03",
            payerId = "p2",
            subtotalCents = 5942,
            taxCents = 210,
            discountCents = 0,
            totalCents = 6152,
            items = listOf(
                ReceiptItemDto(
                    id = "i1",
                    rawText = "CILANTRO BNCH",
                    description = "Fresh Cilantro",
                    totalPriceCents = 129,
                    owners = listOf(ItemOwnerDto("p1"), ItemOwnerDto("p3", weight = 2)),
                ),
                ReceiptItemDto("i2", "EGGS LRG 18CT", "Eggs 18ct", quantity = 1.5, totalPriceCents = 549),
            ),
        )
        assertEquals(receipt, roundTrip(receipt))
    }

    @Test
    fun money_is_serialized_as_integer_cents() {
        val text = json.encodeToString(BalanceDto("p1", netCents = 3410))
        assertEquals("""{"personId":"p1","netCents":3410,"currency":"USD"}""", text)
    }

    @Test
    fun large_amounts_keep_full_precision() {
        val big = SettlementDto("s1", "h1", "p1", "p2", amountCents = 9_007_199_254_740_993L)
        assertEquals(big, roundTrip(big))
    }

    @Test
    fun receipt_status_uses_wire_names() {
        assertEquals("\"needs_review\"", json.encodeToString(ReceiptStatusDto.NeedsReview))
        assertEquals(ReceiptStatusDto.Processing, json.decodeFromString<ReceiptStatusDto>("\"processing\""))
        assertEquals(ReceiptStatusDto.Failed, json.decodeFromString<ReceiptStatusDto>("\"failed\""))
    }

    @Test
    fun settlement_and_balance_round_trip() {
        val settlement = SettlementDto("s1", "h1", "p1", "p2", 1240, createdAt = "2026-10-03T10:00:00Z")
        assertEquals(settlement, roundTrip(settlement))
        val balance = BalanceDto("p1", netCents = -985, currency = "EUR")
        assertEquals(balance, roundTrip(balance))
    }

    @Test
    fun api_error_decodes_with_and_without_details() {
        val withDetails = json.decodeFromString<ApiError>(
            """{"code":"validation_error","message":"Invalid request","details":["name must not be blank"]}""",
        )
        assertEquals(listOf("name must not be blank"), withDetails.details)
        val bare = json.decodeFromString<ApiError>("""{"code":"not_found","message":"Not found"}""")
        assertEquals(emptyList(), bare.details)
    }

    @Test
    fun optional_fields_default_when_missing() {
        val receipt = json.decodeFromString<ReceiptDto>(
            """{"id":"r1","householdId":"h1","status":"processing"}""",
        )
        assertEquals(DEFAULT_CURRENCY, receipt.currency)
        assertEquals(emptyList(), receipt.items)
        assertEquals(null, receipt.totalCents)
    }
}
