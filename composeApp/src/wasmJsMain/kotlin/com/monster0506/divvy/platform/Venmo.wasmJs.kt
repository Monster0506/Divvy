package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@OptIn(ExperimentalWasmJsInterop::class)
private fun openInNewTab(url: String) {
    js("window.open(url, '_blank')")
}

@Composable
actual fun rememberUrlOpener(): UrlOpener = remember {
    UrlOpener { url, onFailure ->
        // Browsers cannot detect a missing app, so only the web link is useful here.
        if (url.startsWith("http")) openInNewTab(url) else onFailure()
    }
}
