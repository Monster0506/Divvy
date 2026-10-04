package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable

/** Opens the device camera; photo handling will be added with receipt upload. */
class CameraCapture internal constructor(val isSupported: Boolean, private val launcher: () -> Unit) {
    fun launch() = launcher()
}

@Composable
expect fun rememberCameraCapture(onError: () -> Unit): CameraCapture
