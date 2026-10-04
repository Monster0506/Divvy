package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher

@Composable
actual fun rememberCameraCapture(onError: () -> Unit): CameraCapture {
    val launcher = rememberCameraPickerLauncher(
        onError = { onError() },
        onResult = {},
    )
    return CameraCapture(isSupported = true) { launcher.launch() }
}
