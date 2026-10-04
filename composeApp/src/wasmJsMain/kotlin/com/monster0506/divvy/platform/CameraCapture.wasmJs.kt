package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberCameraCapture(onError: () -> Unit): CameraCapture =
    CameraCapture(isSupported = false) { onError() }
