package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.theme.DivvyColors

/** What the review flow shows while the photo is being read, or when it could not be read or loaded. */
@Composable
fun ReviewStatusScreen(
    title: String,
    message: String?,
    busy: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    onBack: () -> Unit,
    onTab: (Tab) -> Unit = {},
) {
    AppScaffold(selected = Tab.Receipts, onSelect = onTab) {
        ScreenHeader(title = title)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) CircularProgressIndicator()
            if (message != null) {
                Text(message, fontSize = 15.sp, color = DivvyColors.Ink, textAlign = TextAlign.Center)
            }
            if (actionLabel != null) PrimaryButton(actionLabel, enabled = true, onClick = onAction)
            Text(
                "Back to receipts",
                Modifier.clickable(onClick = onBack).padding(12.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DivvyColors.Dim,
            )
        }
    }
}
