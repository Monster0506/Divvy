package com.monster0506.divvy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * While this is on screen, calls [action] every [everyMillis] as long as the app is in the foreground, and once
 * as it comes back from the background. Nothing runs while the app is in the background or after this leaves.
 */
@Composable
fun RepeatWhileInForeground(everyMillis: Long, action: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(action)
    LaunchedEffect(lifecycle, everyMillis) {
        var wasAway = false
        lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.RESUMED) }
            .distinctUntilChanged()
            .collectLatest { inForeground ->
                if (!inForeground) {
                    wasAway = true
                    return@collectLatest
                }
                if (wasAway) current()
                while (true) {
                    delay(everyMillis)
                    current()
                }
            }
    }
}
