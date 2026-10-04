package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

@Composable
actual fun rememberCameraCapture(onResult: (ByteArray) -> Unit, onError: () -> Unit): CameraCapture {
    val scope = rememberCoroutineScope()
    val launcher = rememberCameraPickerLauncher(
        onError = { onError() },
        onResult = { file ->
            if (file != null) scope.launch {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null) onError() else onResult(bytes)
            }
        },
    )
    return CameraCapture(isSupported = true) { launcher.launch() }
}
