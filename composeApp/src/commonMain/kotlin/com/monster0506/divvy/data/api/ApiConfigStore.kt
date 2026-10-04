package com.monster0506.divvy.data.api

import com.russhwolf.settings.Settings
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Device-specific server settings; build-time values remain the defaults. */
class ApiConfigStore(private val settings: Settings) {
    fun load(defaults: ApiConfig = ApiConfig()): ApiConfig = ApiConfig(
        baseUrl = settings.getStringOrNull(BASE_URL) ?: defaults.baseUrl,
        apiKey = settings.getStringOrNull(API_KEY) ?: defaults.apiKey,
    )

    fun save(config: ApiConfig) {
        settings.putString(BASE_URL, config.baseUrl)
        settings.putString(API_KEY, config.apiKey)
    }

    private companion object {
        const val BASE_URL = "api_base_url"
        const val API_KEY = "api_key"
    }
}

fun ApiConfig.normalizedOrNull(): ApiConfig? {
    val address = baseUrl.trim().trimEnd('/')
    val parsed = try {
        Url(address)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (parsed.protocol != URLProtocol.HTTP && parsed.protocol != URLProtocol.HTTPS) return null
    if (parsed.host.isBlank() || !parsed.user.isNullOrBlank() || !parsed.password.isNullOrBlank()) return null
    if ('?' in address || '#' in address) return null
    val key = apiKey.trim()
    if (key.isEmpty()) return null
    return ApiConfig(address, key)
}
