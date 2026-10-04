package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TimestampedTable
import com.monster0506.divvy.server.db.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/** Mapping for `household_ml_settings` (see V8). Never call SchemaUtils.create on it. */
object HouseholdMlSettingsTable : TimestampedTable("household_ml_settings") {
    val householdId = uuid("household_id")
    val autoAssign = bool("auto_assign")
    val autoAssignThreshold = decimal("auto_assign_threshold", 4, 3)

    override val primaryKey = PrimaryKey(householdId)
}

class ExposedMlSettingsRepository(private val db: Database) : MlSettingsRepository {
    override suspend fun get(householdId: Uuid): MlSettings = db.dbQuery {
        HouseholdMlSettingsTable.selectAll()
            .where { HouseholdMlSettingsTable.householdId eq householdId }
            .singleOrNull()
            ?.let { MlSettings(it[HouseholdMlSettingsTable.autoAssign], it[HouseholdMlSettingsTable.autoAssignThreshold].toDouble()) }
            ?: MlSettings()
    }

    override suspend fun save(householdId: Uuid, settings: MlSettings): MlSettings {
        db.dbQuery {
            HouseholdMlSettingsTable.upsert(HouseholdMlSettingsTable.householdId) {
                it[HouseholdMlSettingsTable.householdId] = householdId
                it[autoAssign] = settings.autoAssign
                it[autoAssignThreshold] = BigDecimal.valueOf(settings.autoAssignThreshold).setScale(3, RoundingMode.HALF_UP)
            }
        }
        return get(householdId)
    }
}
