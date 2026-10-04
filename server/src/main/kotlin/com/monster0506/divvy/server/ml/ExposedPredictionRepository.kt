package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TimestampedTable
import com.monster0506.divvy.server.db.dbQuery
import com.monster0506.divvy.server.db.ReceiptItemTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/** Mapping for `item_prediction` (see V7). Never call SchemaUtils.create on it. */
object ItemPredictionTable : TimestampedTable("item_prediction") {
    val receiptItemId = uuid("receipt_item_id")
    val householdId = uuid("household_id")
    val modelVersion = text("model_version")
    val status = text("status")
    val confidence = decimal("confidence", 5, 4)
    val alternatives = text("alternatives")

    override val primaryKey = PrimaryKey(receiptItemId)
}

@Serializable
private data class AlternativeJson(val owners: List<String>, val confidence: Double)

private val alternativesSerializer = ListSerializer(AlternativeJson.serializer())

class ExposedPredictionRepository(private val db: Database) : PredictionRepository {
    override suspend fun save(predictions: List<StoredPrediction>) {
        if (predictions.isEmpty()) return
        db.dbQuery {
            predictions.forEach { p ->
                ItemPredictionTable.upsert(
                    ItemPredictionTable.receiptItemId,
                    onUpdateExclude = listOf(ItemPredictionTable.householdId),
                ) {
                    it[receiptItemId] = p.receiptItemId
                    it[householdId] = p.householdId
                    it[modelVersion] = p.modelVersion
                    it[status] = p.status.name.lowercase()
                    it[confidence] = BigDecimal.valueOf(p.confidence).setScale(4, RoundingMode.HALF_UP)
                    it[alternatives] = mlJson.encodeToString(alternativesSerializer, p.alternatives.map(::toJson))
                }
            }
        }
    }

    override suspend fun forReceipt(receiptId: Uuid): List<StoredPrediction> = db.dbQuery {
        val itemIds = ReceiptItemTable.select(ReceiptItemTable.id)
            .where { ReceiptItemTable.receiptId eq receiptId }
            .map { it[ReceiptItemTable.id] }
        if (itemIds.isEmpty()) return@dbQuery emptyList()

        ItemPredictionTable.selectAll()
            .where { ItemPredictionTable.receiptItemId inList itemIds }
            .map { row ->
                StoredPrediction(
                    receiptItemId = row[ItemPredictionTable.receiptItemId],
                    householdId = row[ItemPredictionTable.householdId],
                    modelVersion = row[ItemPredictionTable.modelVersion],
                    status = MlPredictionStatus.valueOf(row[ItemPredictionTable.status].uppercase()),
                    confidence = row[ItemPredictionTable.confidence].toDouble(),
                    alternatives = mlJson.decodeFromString(alternativesSerializer, row[ItemPredictionTable.alternatives])
                        .map { StoredAlternative(it.owners.map(Uuid::parse), it.confidence) },
                    predictedAt = row[ItemPredictionTable.updatedAt].toInstant(),
                )
            }
    }

    override suspend fun countForHousehold(householdId: Uuid): Int = db.dbQuery {
        ItemPredictionTable.select(ItemPredictionTable.receiptItemId.count())
            .where { ItemPredictionTable.householdId eq householdId }
            .single()[ItemPredictionTable.receiptItemId.count()]
            .toInt()
    }

    private fun toJson(alternative: StoredAlternative) =
        AlternativeJson(alternative.ownerIds.map { it.toString() }, alternative.confidence)
}
