package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Composable
actual fun rememberUrlOpener(): UrlOpener = remember {
    UrlOpener { url, onFailure ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            onFailure()
        } else {
            UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>()) { opened ->
                if (!opened) onFailure()
            }
        }
    }
}
