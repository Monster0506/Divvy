package com.monster0506.divvy.server

import com.monster0506.divvy.server.db.DatabaseFactory
import com.monster0506.divvy.server.db.DivyDatabase
import com.monster0506.divvy.server.ml.LabelSync
import com.monster0506.divvy.server.ml.ExposedPredictionRepository
import com.monster0506.divvy.server.ml.MlIntegration
import com.monster0506.divvy.server.ml.ConfirmationGuardReceiptRepository
import com.monster0506.divvy.server.ml.ExtractionService
import com.monster0506.divvy.server.ml.ExposedMlSettingsRepository
import com.monster0506.divvy.server.ml.MlStats
import com.monster0506.divvy.server.ml.PredictionService
import com.monster0506.divvy.server.ml.MlResync
import com.monster0506.divvy.server.ml.SyncingReceiptRepository
import com.monster0506.divvy.server.ml.createMlClient
import com.monster0506.divvy.server.repository.ExposedPersonRepository
import com.monster0506.divvy.server.repository.ExposedRepositories
import com.monster0506.divvy.server.routes.HouseholdNameAsId
import com.monster0506.divvy.server.routes.householdRoutes
import com.monster0506.divvy.server.routes.listItemRoutes
import com.monster0506.divvy.server.routes.mlRoutes
import com.monster0506.divvy.server.routes.mlSettingsRoutes
import com.monster0506.divvy.server.routes.mlStatsRoutes
import com.monster0506.divvy.server.routes.personArchiveRoutes
import com.monster0506.divvy.server.routes.predictionRoutes
import com.monster0506.divvy.server.routes.personRoutes
import com.monster0506.divvy.server.routes.receiptAssignmentRoutes

import com.monster0506.divvy.server.routes.receiptListRoutes
import com.monster0506.divvy.server.routes.receiptEditRoutes
import com.monster0506.divvy.server.routes.receiptReadRoutes
import com.monster0506.divvy.server.routes.receiptReopenRoutes
import com.monster0506.divvy.server.routes.receiptRetryRoutes
import com.monster0506.divvy.server.routes.receiptRoutes
import com.monster0506.divvy.server.routes.settlementRoutes
import com.monster0506.divvy.server.routes.settlementRoutes
import com.monster0506.divvy.server.routes.balanceRoutes
import com.monster0506.divvy.server.storage.ImageStorage
import com.monster0506.divvy.server.storage.LazyImageStorage
import com.monster0506.divvy.server.storage.LocalFileImageStorage
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.nio.file.Path
import java.security.MessageDigest
import io.ktor.server.application.application

@Serializable
data class HealthResponse(val status: String, val version: String)

/**
 * Entry point loaded by `io.ktor.server.netty.EngineMain` from `application.conf`.
 * Tests that have no database pass [connectDatabase] = false.
 */
fun Application.module(connectDatabase: Boolean = true) {
    val config = AppConfig.from(environment.config)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} " +
                    "${call.response.status()?.value} ${call.processingTimeMillis()}ms"
        }
    }
    install(Authentication) {
        bearer(API_KEY_AUTH) {
            authHeader { call ->
                call.request.header(API_KEY_HEADER)?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            authenticate { credential ->
                val expectedKey = config.apiKey
                if (expectedKey != null && keysMatch(credential.token, expectedKey)) {
                    UserIdPrincipal("api-key")
                } else {
                    null
                }
            }
        }
    }
    install(StatusPages) {
        exception<ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("validation_error", cause.message ?: "Invalid request", cause.details),
            )
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", cause.message ?: "Not found"))
        }
        exception<InvalidTransitionException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("invalid_transition", cause.message ?: "Invalid status transition"),
            )
        }
        exception<UnconfirmedItemsException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("unconfirmed_items", cause.message ?: "Items need confirming", cause.itemIds.map { it.toString() }),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Internal server error"))
        }
    }

    install(HouseholdNameAsId) { households = { repositories.households } }
    routing {
        get("/health") { call.respond(HealthResponse("ok", config.version)) }
        authenticate(API_KEY_AUTH) {
            // Lets clients tell a wrong API key apart from an unreachable server; /health is public.
            get("/auth/check") { call.respond(HttpStatusCode.NoContent) }
            householdRoutes { repositories.households }
            personArchiveRoutes({ repositories.households }, { repositories.people })
            personRoutes({ repositories.households }, { repositories.people })
            mlRoutes({ repositories.households }, { ml.resync })
            mlStatsRoutes({ repositories.households }, { ml.stats })
            mlSettingsRoutes({ repositories.households }, { ml.settings })
            predictionRoutes({ repositories.receipts }, { ml.predictions })
            receiptAssignmentRoutes({ repositories.receipts }, { repositories.itemOwners }, { repositories.people })

            receiptListRoutes({ repositories.households }, { repositories.receipts })
            receiptRoutes(
                { repositories.households }, { repositories.receipts }, { imageStorage }, config.maxUploadBytes,
                onUploaded = { ml.extraction.schedule(it) },
            )
            receiptRetryRoutes({ repositories.receipts }, { ml.extraction })
            receiptReopenRoutes({ repositories.receipts })
            receiptEditRoutes({ repositories.receipts }, { repositories.people })
            settlementRoutes({ repositories.households }, { repositories.people }, { repositories.settlements })
            receiptReadRoutes({ repositories.receipts }, { imageStorage })
            balanceRoutes({ repositories.households }, { repositories.people }, { repositories.ledger })
            listItemRoutes({ repositories.households }, { repositories.people }, { repositories.listItems })
        }
    }

    if (connectDatabase) configureDatabase(config)
}

private val RepositoriesKey = AttributeKey<ExposedRepositories>("repositories")
private val MlKey = AttributeKey<MlIntegration>("ml")
private val ImageStorageKey = AttributeKey<ImageStorage>("imageStorage")

/** Uploaded receipt images. Available once [configureDatabase] has run; the directory is created on first use. */
val Application.imageStorage: ImageStorage get() = attributes[ImageStorageKey]

/** The ML client and label sync. Available once [configureDatabase] has run. */
val Application.ml: MlIntegration get() = attributes[MlKey]

/** The repositories routes use for data access. Available once [configureDatabase] has run. */
val Application.repositories: ExposedRepositories get() = attributes[RepositoriesKey]

/**
 * Connects to PostgreSQL and applies pending Flyway migrations before the server accepts
 * requests, then registers [repositories]. Startup fails if the database is unreachable or a
 * migration fails.
 */
fun Application.configureDatabase(config: AppConfig): DivyDatabase {
    val database = DatabaseFactory.connect(config)
    val mlClient = createMlClient(config)
    val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val labelSync = LabelSync(mlClient, ExposedPersonRepository(database.database), syncScope)
    monitor.subscribe(ApplicationStopped) {
        syncScope.cancel()
        (mlClient as? AutoCloseable)?.close()
        database.close()
    }
    val settings = ExposedMlSettingsRepository(database.database)
    val repos = ExposedRepositories(database.database) {
        ConfirmationGuardReceiptRepository(SyncingReceiptRepository(it, labelSync), settings)
    }
    val resync = MlResync(mlClient, repos.receipts, repos.people, labelSync)
    val predictionRepository = ExposedPredictionRepository(database.database)
    val predictions = PredictionService(
        mlClient, resync, repos.receipts, repos.people, repos.itemOwners, predictionRepository, settings,
    )
    val storage = LazyImageStorage { LocalFileImageStorage(Path.of(config.imageDir)) }
    val extraction = ExtractionService(mlClient, repos.receipts, storage, predictions, syncScope)
    attributes.put(
        MlKey,
        MlIntegration(
            mlClient, labelSync, resync, predictions, MlStats(repos.receipts, predictionRepository), settings, extraction,
        ),
    )
    attributes.put(RepositoriesKey, repos)
    attributes.put(ImageStorageKey, storage)
    syncScope.launch { extraction.resumePending() }
    syncScope.launch {
        val repaired = repos.repairConfirmedReceipts()
        if (repaired > 0) environment.log.info("Gave {} confirmed receipts the balances and labels they were missing", repaired)
    }
    return database
}

const val API_KEY_AUTH = "api-key"
const val API_KEY_HEADER = "X-Api-Key"

private fun keysMatch(provided: String, expected: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    return MessageDigest.isEqual(
        digest.digest(provided.toByteArray()),
        digest.digest(expected.toByteArray()),
    )
}