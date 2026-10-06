package com.monster0506.divvy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.connection.ConnectionStatus
import com.monster0506.divvy.ui.theme.DivvyColors

/** Whether [ConnectionBanner] has anything to show for [status]. */
fun connectionBannerVisible(status: ConnectionStatus): Boolean =
    status == ConnectionStatus.Unreachable || status == ConnectionStatus.KeyRejected

/**
 * The one place the app says the server can't be used. It draws behind the status bar itself,
 * so the screen below should consume the top inset while it is visible.
 *
 * @param onServerSettings opens Account > Server; null hides the link (e.g. already on Account).
 */
@Composable
fun ConnectionBanner(
    status: ConnectionStatus,
    onServerSettings: (() -> Unit)?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!connectionBannerVisible(status)) return
    val (title, detail) = when (status) {
        ConnectionStatus.KeyRejected -> "API key rejected" to "The server doesn't accept this key."
        else -> "Can't reach the server" to "Retrying in the background."
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(DivvyColors.WarnBg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DivvyColors.Warn)
        Text(detail, fontSize = 13.sp, color = DivvyColors.Ink)
        Row(Modifier.padding(top = 6.dp)) {
            if (onServerSettings != null) {
                BannerAction("Server settings", onServerSettings)
                Spacer(Modifier.width(20.dp))
            }
            BannerAction("Retry now", onRetry)
        }
    }
}

/**
 * Puts [ConnectionBanner] above [content]. While the banner shows, it takes the top inset, so
 * screens that pad for `safeDrawing` don't leave a second status-bar gap under it.
 */
@Composable
fun ConnectionFrame(
    status: ConnectionStatus,
    onServerSettings: (() -> Unit)?,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visible = connectionBannerVisible(status)
    Column(Modifier.fillMaxSize()) {
        ConnectionBanner(status, onServerSettings, onRetry)
        val consumed = if (visible) {
            Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        } else {
            Modifier
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(consumed)) { content() }
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) = Text(
    label,
    modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    color = DivvyColors.Accent,
)

@Preview(widthDp = 390, showBackground = true)
@Composable
private fun ConnectionBannerPreview() = Column {
    ConnectionBanner(ConnectionStatus.Unreachable, onServerSettings = {}, onRetry = {})
    ConnectionBanner(ConnectionStatus.KeyRejected, onServerSettings = {}, onRetry = {})
}
