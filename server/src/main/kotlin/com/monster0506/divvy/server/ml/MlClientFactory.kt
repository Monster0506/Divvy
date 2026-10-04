package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.AppConfig

/** The client for [AppConfig.mlServiceUrl], or [NoopMlClient] when no ML service is configured. */
fun createMlClient(config: AppConfig): MlClient =
    config.mlServiceUrl?.takeIf { it.isNotBlank() }?.let { KtorMlClient.create(it) } ?: NoopMlClient
