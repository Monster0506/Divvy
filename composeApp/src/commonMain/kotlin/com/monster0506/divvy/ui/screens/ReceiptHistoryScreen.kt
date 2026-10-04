package com.monster0506.divvy.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.HistoryEntry
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.model.toMoney
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

@Composable
fun ReceiptHistoryScreen(
    entries: List<HistoryEntry>,
    onOpen: (index: Int) -> Unit,
    onScan: () -> Unit = {},
    onTab: (Tab) -> Unit = {},
) {
    AppScaffold(selected = Tab.Receipts, onSelect = onTab) {
        ScreenHeader("Receipts", metaStart = "Newest first", action = {
            TextButton(onClick = onScan) {
                Text("Scan receipt", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        })
        entries.forEachIndexed { i, e ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.store, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                    Text("${e.date} - paid by ${e.payer.name}", fontSize = 12.sp, color = DivvyColors.Dim)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(e.totalCents.toMoney(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
                    val review = e.status == ReceiptStatus.NeedsReview
                    val tint by animateColorAsState(if (review) DivvyColors.Warn else DivvyColors.Dim, tween(300))
                    AnimatedContent(e.status) { status ->
                        Text(status.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint, textAlign = TextAlign.End)
                    }
                }
            }
            RowDivider()
        }
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ReceiptHistoryPreview() = DivvyTheme {
    ReceiptHistoryScreen(SampleData.history, {})
}
