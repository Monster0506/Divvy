package com.monster0506.divvy.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/*
 * Exposed mappings for the tables created by the Flyway migrations in
 * `src/main/resources/db/migration`. The migrations are the source of truth for the schema
 * (constraints, composite foreign keys, triggers); these objects only describe columns so the
 * repositories can query them. Never call SchemaUtils.create on them.
 *
 * Columns filled by the database (ids, timestamps, defaults) are marked databaseGenerated()
 * and read back with insertReturning / updateReturning.
 */

/** Columns every table shares. updated_at is maintained by a trigger. */
abstract class TimestampedTable(name: String) : Table(name) {
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()
}

object HouseholdTable : TimestampedTable("household") {
    val id = uuid("id").databaseGenerated()
    val name = text("name")
    val defaultCurrency = char("default_currency", 3)

    override val primaryKey = PrimaryKey(id)
}

object PersonTable : TimestampedTable("person") {
    val id = uuid("id").databaseGenerated()
    val householdId = uuid("household_id")
    val displayName = text("display_name")
    val archivedAt = timestampWithTimeZone("archived_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ReceiptTable : TimestampedTable("receipt") {
    val id = uuid("id").databaseGenerated()
    val householdId = uuid("household_id")
    val paidByPersonId = uuid("paid_by_person_id")
    val merchantName = text("merchant_name").nullable()
    val purchasedAt = timestampWithTimeZone("purchased_at")
    val currency = char("currency", 3)
    val subtotalMinor = long("subtotal_minor").nullable()
    val taxMinor = long("tax_minor").nullable()
    val totalMinor = long("total_minor")
    val status = text("status").databaseGenerated()
    val confirmedAt = timestampWithTimeZone("confirmed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ReceiptItemTable : TimestampedTable("receipt_item") {
    val id = uuid("id").databaseGenerated()
    val householdId = uuid("household_id")
    val receiptId = uuid("receipt_id")
    val lineNumber = integer("line_number")
    val rawText = text("raw_text").nullable()
    val description = text("description")
    val quantity = decimal("quantity", 12, 3)
    val unitPriceMinor = long("unit_price_minor").nullable()
    val discountMinor = long("discount_minor")
    val totalMinor = long("total_minor")
    val labelSource = text("label_source").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ItemOwnerTable : TimestampedTable("item_owner") {
    val householdId = uuid("household_id")
    val receiptItemId = uuid("receipt_item_id")
    val personId = uuid("person_id")
    val shareWeight = integer("share_weight")
    val ownershipSource = text("source")
    val confidence = decimal("confidence", 5, 4).nullable()

    override val primaryKey = PrimaryKey(receiptItemId, personId)
}

object ItemOwnerHistoryTable : TimestampedTable("item_owner_history") {
    val id = long("id").databaseGenerated()
    val householdId = uuid("household_id")
    val receiptItemId = uuid("receipt_item_id")
    val personId = uuid("person_id")
    val operation = text("operation")
    val shareWeight = integer("share_weight")
    val ownershipSource = text("source")
    val confidence = decimal("confidence", 5, 4).nullable()
    val changedAt = timestampWithTimeZone("changed_at")

    override val primaryKey = PrimaryKey(id)
}

object SettlementTable : TimestampedTable("settlement") {
    val id = uuid("id").databaseGenerated()
    val householdId = uuid("household_id")
    val fromPersonId = uuid("from_person_id")
    val toPersonId = uuid("to_person_id")
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3)
    val settledAt = timestampWithTimeZone("settled_at").databaseGenerated()
    val note = text("note").nullable()

    override val primaryKey = PrimaryKey(id)
}