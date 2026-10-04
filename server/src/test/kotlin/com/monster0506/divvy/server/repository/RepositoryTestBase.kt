package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.BeforeTest

abstract class RepositoryTestBase {
    protected val repos = ExposedRepositories(TestDatabase.db.database)
    protected val usd = CurrencyCode("USD")

    @BeforeTest
    fun resetDatabase() = TestDatabase.reset()

    protected suspend fun household(vararg names: String): Pair<Household, List<Person>> {
        val household = repos.households.create("Apartment 4B", usd)
        return household to names.map { repos.people.add(household.id, it) }
    }

    protected suspend fun receipt(household: Household, paidBy: Person, vararg items: Pair<String, Long>): Receipt =
        repos.receipts.create(
            NewReceipt(
                householdId = household.id,
                paidByPersonId = paidBy.id,
                merchantName = "Trader Joe's",
                purchasedAt = Instant.parse("2026-09-30T17:45:00Z"),
                currency = usd,
                subtotalMinor = items.sumOf { it.second },
                taxMinor = 0,
                totalMinor = items.sumOf { it.second },
                items = items.map { (description, total) -> NewReceiptItem(description, total, BigDecimal.ONE, total) },
            ),
        )
}