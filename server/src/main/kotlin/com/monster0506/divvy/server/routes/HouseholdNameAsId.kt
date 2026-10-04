package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.repository.HouseholdRepository
import io.ktor.http.decodeURLPart
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.plugins.mutableOriginConnectionPoint
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class HouseholdNameAsIdConfig {
    var households: () -> HouseholdRepository = { error("households not set") }
}

/**
 * Lets a household's name stand in for its id in `/households/{x}/...`: a segment that is not a UUID is looked up by
 * name and the request is rewritten to the real id before routing, so no route needs to know. Unknown names fall
 * through and get the usual 404.
 */
@OptIn(ExperimentalUuidApi::class)
val HouseholdNameAsId = createApplicationPlugin("HouseholdNameAsId", ::HouseholdNameAsIdConfig) {
    val households = pluginConfig.households
    onCall { call ->
        val segments = call.request.path().split('/')
        if (segments.size < 3 || segments[1] != "households" || segments[2].isEmpty()) return@onCall
        val name = runCatching { segments[2].decodeURLPart() }.getOrNull() ?: return@onCall
        if (runCatching { Uuid.parse(name) }.isSuccess) return@onCall
        val household = households().findByName(name.trim()) ?: return@onCall
        val rest = segments.drop(3).joinToString("") { "/$it" }
        val query = call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }
        call.mutableOriginConnectionPoint.uri = "/households/${household.id}$rest$query"
    }
}
