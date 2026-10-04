package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

/** Opens the system photo picker (Photo Picker on Android, PHPicker on iOS). */
class GalleryPicker internal constructor(private val launcher: () -> Unit) {
    fun launch() = launcher()
}

/** [onResult] gets the image bytes, or null if the user cancelled or the file could not be read. */
@Composable
fun rememberGalleryPicker(onResult: (ByteArray?) -> Unit): GalleryPicker {
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file == null) {
            onResult(null)
        } else {
            scope.launch { onResult(runCatching { file.readBytes() }.getOrNull()) }
        }
    }
    return GalleryPicker { launcher.launch() }
}
