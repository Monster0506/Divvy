package com.monster0506.divvy.server

import io.ktor.server.config.ApplicationConfig

/**
 * Server configuration, read once at startup so the same image runs locally, in CI and under
 * Docker Compose. The server reads it from `application.conf` via [from], which maps environment
 * variables into the `divvy` block; [fromEnv] reads the environment directly.
 */
data class AppConfig(
    val port: Int,
    val database: DatabaseSettings,
    val version: String = DEFAULT_VERSION,
    val mlServiceUrl: String? = null,
    val apiKey: String? = null,
) {
    data class DatabaseSettings(
        /** JDBC URL, e.g. `jdbc:postgresql://db:5432/divvy`. */
        val jdbcUrl: String,
        val user: String,
        val password: String,
        val maxPoolSize: Int = DEFAULT_POOL_SIZE,
    ) {
        init {
            require(jdbcUrl.startsWith("jdbc:postgresql:")) { "DATABASE_URL must be a jdbc:postgresql: URL" }
            require(maxPoolSize > 0) { "DATABASE_POOL_SIZE must be positive" }
        }

        // Keep the password out of logs and exception messages.
        override fun toString(): String =
            "DatabaseSettings(jdbcUrl=$jdbcUrl, user=$user, password=***, maxPoolSize=$maxPoolSize)"
    }

    // Keep the API key out of logs and exception messages.
    override fun toString(): String =
        "AppConfig(port=$port, database=$database, version=$version, mlServiceUrl=$mlServiceUrl, apiKey=***)"

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_POOL_SIZE = 10
        const val DEFAULT_VERSION = "dev"

        /** `divvy.*` config keys and the environment variables that fill them in `application.conf`. */
        private val requiredSettings = mapOf(
            "divvy.database.url" to "DATABASE_URL",
            "divvy.database.user" to "DATABASE_USER",
            "divvy.database.password" to "DATABASE_PASSWORD",
            "divvy.ml.url" to "ML_SERVICE_URL",
            "divvy.apiKey" to "API_KEY",
        )

        /**
         * Reads the `divvy` block of [config]. Fails with a message naming the environment variable
         * behind every missing setting.
         */
        fun from(config: ApplicationConfig): AppConfig {
            fun value(key: String): String? = config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }

            val missing = requiredSettings.filterKeys { value(it) == null }.values
            check(missing.isEmpty()) { "Missing required environment variables: ${missing.joinToString()}" }
            return AppConfig(
                port = value("ktor.deployment.port")?.toIntOrNull() ?: DEFAULT_PORT,
                database = DatabaseSettings(
                    jdbcUrl = value("divvy.database.url")!!,
                    user = value("divvy.database.user")!!,
                    password = value("divvy.database.password")!!,
                    maxPoolSize = value("divvy.database.poolSize")?.toIntOrNull() ?: DEFAULT_POOL_SIZE,
                ),
                version = value("divvy.version") ?: DEFAULT_VERSION,
                mlServiceUrl = value("divvy.ml.url")!!,
                apiKey = value("divvy.apiKey")!!,
            )
        }

        /**
         * Reads `PORT` (optional), `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` and
         * `DATABASE_POOL_SIZE` (optional). Fails with a message naming every missing variable.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val required = listOf("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD")
            val missing = required.filter { env[it].isNullOrBlank() }
            require(missing.isEmpty()) { "Missing required environment variables: ${missing.joinToString()}" }
            return AppConfig(
                port = env["PORT"]?.toIntOrNull() ?: DEFAULT_PORT,
                database = DatabaseSettings(
                    jdbcUrl = env.getValue("DATABASE_URL"),
                    user = env.getValue("DATABASE_USER"),
                    password = env.getValue("DATABASE_PASSWORD"),
                    maxPoolSize = env["DATABASE_POOL_SIZE"]?.toIntOrNull() ?: DEFAULT_POOL_SIZE,
                ),
                version = env["APP_VERSION"] ?: DEFAULT_VERSION,
                mlServiceUrl = env["ML_SERVICE_URL"],
                apiKey = env["API_KEY"],
            )
        }
    }
}
