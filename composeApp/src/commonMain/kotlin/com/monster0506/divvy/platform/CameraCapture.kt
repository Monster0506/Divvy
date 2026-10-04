package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable

/** Opens the device camera and returns the captured photo bytes. Cancellation leaves the selection alone. */
class CameraCapture internal constructor(val isSupported: Boolean, private val launcher: () -> Unit) {
    fun launch() = launcher()
}

@Composable
expect fun rememberCameraCapture(onResult: (ByteArray) -> Unit, onError: () -> Unit): CameraCapture
