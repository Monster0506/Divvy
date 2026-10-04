package com.monster0506.divvy.server.ml

/** The ML client, the label sync, the resync, predictions, stats and settings the application shares. */
class MlIntegration(
    val client: MlClient,
    val labelSync: LabelSync,
    val resync: MlResync,
    val predictions: PredictionService,
    val stats: MlStats,
    val settings: MlSettingsRepository,
    val extraction: ExtractionService,
)
