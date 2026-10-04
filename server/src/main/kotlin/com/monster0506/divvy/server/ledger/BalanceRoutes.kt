package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.LedgerEntry
import com.monster0506.divvy.server.ledger.CurrencyBalances
import com.monster0506.divvy.server.ledger.balancesByCurrency
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.LedgerRepository
import com.monster0506.divvy.server.repository.PersonRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One counterparty and how much is owed between them and the person it sits under. */
@Serializable
data class DebtResponse(val personId: String, val amountMinor: Long)

@Serializable
data class PersonBalanceResponse(
    val personId: String,
    val displayName: String,
    /** Positive: owed money. Negative: owes money. */
    val netMinor: Long,
    /** People this person owes, after netting opposite debts. */
    val owes: List<DebtResponse>,
    /** People who owe this person, after netting opposite debts. */
    val owedBy: List<DebtResponse>,
)

@Serializable
data class CurrencyBalancesResponse(val currency: String, val people: List<PersonBalanceResponse>)

@Serializable
data class BalancesResponse(val balances: List<CurrencyBalancesResponse>)

@Serializable
data class LedgerEntryResponse(
    val id: String,
    val debtorPersonId: String,
    val creditorPersonId: String,
    val amountMinor: Long,
    val currency: String,
    /** `receipt` or `settlement`; with [sourceId] this identifies what produced the entry. */
    val sourceType: String,
    val sourceId: String,
    /** Set when this entry cancels an earlier one. */
    val reversesEntryId: String? = null,
    /** Only when filtering by person: +amount if they are the creditor, -amount if the debtor. */
    val personEffectMinor: Long? = null,
    val createdAt: String,
)

private fun LedgerEntry.toResponse(personId: Uuid?) = LedgerEntryResponse(
    id = id.toString(),
    debtorPersonId = debtorPersonId.toString(),
    creditorPersonId = creditorPersonId.toString(),
    amountMinor = amount.amountMinor,
    currency = amount.currency.code,
    sourceType = sourceType.dbValue,
    sourceId = sourceId.toString(),
    reversesEntryId = reversesEntryId?.toString(),
    personEffectMinor = when (personId) {
        null -> null
        creditorPersonId -> amount.amountMinor
        else -> -amount.amountMinor
    },
    createdAt = createdAt.toString(),
)

@OptIn(ExperimentalUuidApi::class)
private fun String.toUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()

@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.householdOrNull(households: HouseholdRepository): Household? =
    parameters["id"].orEmpty().toUuidOrNull()?.let { households.findById(it) }

private suspend fun ApplicationCall.badRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ApiError("validation_error", message))

/** Dependencies are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.balanceRoutes(
    households: () -> HouseholdRepository,
    people: () -> PersonRepository,
    ledger: () -> LedgerRepository,
) {
    route("/households/{id}") {
        get("/balances") {
            val household = call.householdOrNull(households())
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "household not found"))

            val members = people().listByHousehold(household.id, includeArchived = true)
            val computed = balancesByCurrency(ledger().listByHousehold(household.id)).associateBy { it.currency }
            // The household's own currency is always reported, so an empty ledger still lists everyone at zero.
            val currencies = (computed.keys + household.defaultCurrency).sortedBy { it.code }

            val response = currencies.map { currency ->
                val balances = computed[currency] ?: CurrencyBalances(currency, emptyMap(), emptyList())
                val involved = balances.debts.flatMap { listOf(it.debtorPersonId, it.creditorPersonId) }.toSet()
                CurrencyBalancesResponse(
                    currency = currency.code,
                    people = members
                        // People who left appear only while they still owe or are owed something.
                        .filter { it.archivedAt == null || it.id in involved }
                        .map { person ->
                            PersonBalanceResponse(
                                personId = person.id.toString(),
                                displayName = person.displayName,
                                netMinor = balances.netByPerson[person.id] ?: 0L,
                                owes = balances.debts.filter { it.debtorPersonId == person.id }
                                    .map { DebtResponse(it.creditorPersonId.toString(), it.amountMinor) },
                                owedBy = balances.debts.filter { it.creditorPersonId == person.id }
                                    .map { DebtResponse(it.debtorPersonId.toString(), it.amountMinor) },
                            )
                        },
                )
            }
            call.respond(BalancesResponse(response))
        }

        get("/ledger") {
            val household = call.householdOrNull(households())
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "household not found"))

            val personParam = call.request.queryParameters["personId"]
            val personId = if (personParam == null) {
                null
            } else {
                val id = personParam.toUuidOrNull()
                    ?: return@get call.badRequest("personId must be a valid id")
                if (people().findById(id)?.householdId != household.id) {
                    return@get call.badRequest("personId is not a member of this household")
                }
                id
            }

            val entries = ledger().listByHousehold(household.id)
                .filter { personId == null || it.debtorPersonId == personId || it.creditorPersonId == personId }
            call.respond(entries.map { it.toResponse(personId) })
        }
    }
}