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
            status = ReceiptStatusDto.Draft,
            merchantName = "Fresh Market",
            purchasedAt = "2026-10-03T12:00:00Z",
            paidByPersonId = "p2",
            subtotal = MoneyDto(5942),
            tax = MoneyDto(210),
            total = MoneyDto(6152),
            items = listOf(
                ReceiptItemDto(
                    id = "i1",
                    rawText = "CILANTRO BNCH",
                    description = "Fresh Cilantro",
                    total = MoneyDto(129),
                    owners = listOf(
                        ItemOwnerReadDto("p1"),
                        ItemOwnerReadDto("p3", shareWeight = 2, source = "model", confidence = 0.93),
                    ),
                ),
                ReceiptItemDto("i2", "Eggs 18ct", MoneyDto(549), quantity = "1.5"),
            ),
        )
        assertEquals(receipt, roundTrip(receipt))
    }

    @Test
    fun the_servers_receipt_response_decodes() {
        val receipt = com.monster0506.divvy.data.api.ApiJson.decodeFromString<ReceiptDto>(
            """
            {"id":"r1","householdId":"h1","paidByPersonId":null,"merchantName":"Walmart",
             "purchasedAt":"2026-09-18T12:00:00Z","subtotal":{"amountMinor":586,"currency":"USD"},
             "tax":{"amountMinor":41,"currency":"USD"},"total":{"amountMinor":627,"currency":"USD"},
             "status":"draft","confirmedAt":null,"hasImage":true,
             "items":[{"id":"i1","lineNumber":1,"rawText":"BANANAS","description":"bananas","quantity":"3.920",
               "unitPrice":{"amountMinor":48,"currency":"USD"},"discount":{"amountMinor":0,"currency":"USD"},
               "total":{"amountMinor":188,"currency":"USD"},
               "owners":[{"personId":"p1","shareWeight":1,"source":"model","confidence":0.93}],"labelSource":null}],
             "createdAt":"2026-10-04T09:00:00Z","updatedAt":"2026-10-04T09:00:05Z","failureReason":null}
            """.trimIndent(),
        )
        assertEquals(ReceiptStatusDto.Draft, receipt.status)
        assertEquals(627L, receipt.total?.amountMinor)
        assertEquals("bananas", receipt.items.single().description)
        assertEquals("model", receipt.items.single().owners.single().source)
        assertEquals(0.93, receipt.items.single().owners.single().confidence)
    }

    @Test
    fun a_failed_receipt_carries_its_reason() {
        val receipt = json.decodeFromString<ReceiptDto>(
            """{"id":"r1","householdId":"h1","status":"failed","failureReason":"No items could be read from the photo."}""",
        )
        assertEquals(ReceiptStatusDto.Failed, receipt.status)
        assertEquals("No items could be read from the photo.", receipt.failureReason)
    }

    @Test
    fun predictions_decode_with_needs_confirmation() {
        val predictions = com.monster0506.divvy.data.api.ApiJson.decodeFromString<PredictionsDto>(
            """{"receiptId":"r1","mlAvailable":true,"modelVersion":"v1","items":[
                {"itemId":"i1","state":"predicted","ownerIds":["p1"],"confidence":0.6,"alternatives":[],"needsConfirmation":true},
                {"itemId":"i2","state":"unknown","ownerIds":[],"alternatives":[]}]}""",
        )
        assertEquals(listOf(true, false), predictions.items.map { it.needsConfirmation })
        assertEquals(null, predictions.items[1].confidence)
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
        assertEquals("\"draft\"", json.encodeToString(ReceiptStatusDto.Draft))
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
        assertEquals(emptyList(), receipt.items)
        assertEquals(null, receipt.total)
        assertEquals(null, receipt.failureReason)
    }
}
