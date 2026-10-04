package com.monster0506.divvy.server.ml

/** The ML client, the label sync and the resync the application shares. */
class MlIntegration(
    val client: MlClient,
    val labelSync: LabelSync,
    val resync: MlResync,
)
