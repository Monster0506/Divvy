package com.monster0506.divvy.server.ml

/** The ML client, the label sync, the resync, predictions and stats the application shares. */
class MlIntegration(
    val client: MlClient,
    val labelSync: LabelSync,
    val resync: MlResync,
    val predictions: PredictionService,
    val stats: MlStats,
)
