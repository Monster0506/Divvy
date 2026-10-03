package com.monster0506.divvy.server

import io.ktor.server.config.ApplicationConfig

data class AppConfig(
    val version: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val mlServiceUrl: String,
    val apiKey: String,
) {
    companion object {
        private val required = listOf(
            "divvy.database.url" to "DATABASE_URL",
            "divvy.database.user" to "DATABASE_USER",
            "divvy.database.password" to "DATABASE_PASSWORD",
            "divvy.ml.url" to "ML_SERVICE_URL",
            "divvy.apiKey" to "API_KEY",
        )

        fun from(config: ApplicationConfig): AppConfig {
            val missing = required.filter { (path, _) -> config.propertyOrNull(path) == null }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "Missing required configuration, set these environment variables: " +
                        missing.joinToString(", ") { it.second },
                )
            }
            return AppConfig(
                version = config.propertyOrNull("divvy.version")?.getString() ?: "dev",
                databaseUrl = config.property("divvy.database.url").getString(),
                databaseUser = config.property("divvy.database.user").getString(),
                databasePassword = config.property("divvy.database.password").getString(),
                mlServiceUrl = config.property("divvy.ml.url").getString(),
                apiKey = config.property("divvy.apiKey").getString(),
            )
        }
    }
}
