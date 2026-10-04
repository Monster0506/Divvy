package com.monster0506.divvy.server.ml

/** The ML client and the label sync the application shares. */
class MlIntegration(
    val client: MlClient,
    val labelSync: LabelSync,
)
