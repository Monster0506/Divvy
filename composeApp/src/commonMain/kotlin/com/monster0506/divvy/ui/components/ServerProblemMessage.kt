package com.monster0506.divvy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.connection.ConnectionStatus
import com.monster0506.divvy.ui.theme.DivvyColors

/** True when the configured server can't be used, which sends the app to Account > Server. */
fun ConnectionStatus.isServerProblem(): Boolean =
    this == ConnectionStatus.Unreachable || this == ConnectionStatus.KeyRejected

/**
 * The one message the app shows when it can't use the server, above the server settings on Account. While
 * [status] is still being checked (for example, right after the settings were saved) it says so. [keyMissing] is
 * true when no API key has been entered yet, as on a fresh install of a release build.
 */
@Composable
fun ServerProblemMessage(status: ConnectionStatus, onRetry: () -> Unit, modifier: Modifier = Modifier, keyMissing: Boolean = false) {
    val (title, detail) = when (status) {
        ConnectionStatus.KeyRejected -> if (keyMissing) {
            "Enter the server's API key" to "Ask whoever runs the server for its key (API_KEY in its .env), paste it below, then save."
        } else {
            "The server rejected the API key" to "Enter the key the server was set up with (API_KEY in its .env), then save."
        }
        ConnectionStatus.Unreachable ->
            "Can't reach the server" to "Check the server URL and API key below, and that this phone is online. Divvy keeps trying in the background."
        else -> "Checking the server…" to "This takes a moment."
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(DivvyColors.WarnBg)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DivvyColors.Warn)
        Text(detail, Modifier.padding(top = 2.dp), fontSize = 13.sp, color = DivvyColors.Ink)
        if (status.isServerProblem()) {
            Text(
                "Retry now",
                Modifier.padding(top = 6.dp).clickable(onClick = onRetry).padding(vertical = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = DivvyColors.Accent,
            )
        }
    }
}

@Preview(widthDp = 390, showBackground = true)
@Composable
private fun ServerProblemMessagePreview() = Column {
    ServerProblemMessage(ConnectionStatus.Unreachable, onRetry = {})
    ServerProblemMessage(ConnectionStatus.KeyRejected, onRetry = {})
    ServerProblemMessage(ConnectionStatus.KeyRejected, onRetry = {}, keyMissing = true)
}
