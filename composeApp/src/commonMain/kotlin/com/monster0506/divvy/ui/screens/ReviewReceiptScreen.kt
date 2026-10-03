package com.monster0506.divvy.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.OwnerPicker
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import com.monster0506.divvy.ui.model.ownersLabel
import com.monster0506.divvy.ui.model.toMoney
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun ReviewReceiptScreen(
    draft: ReceiptDraft,
    autoExpanded: Boolean,
    onToggleOwner: (lineId: Int, personId: String) -> Unit,
    onConfirmLine: (lineId: Int) -> Unit,
    onToggleAuto: () -> Unit,
    onConfirmSplit: () -> Unit,
    onTab: (Tab) -> Unit = {},
) {
    val pendingCount = draft.lines.count { !it.confirmed }
    val autoCount = draft.lines.size - pendingCount
    AppScaffold(selected = Tab.Receipts, onSelect = onTab) {
        ScreenHeader(
            title = draft.store,
            metaStart = draft.date,
            metaEnd = buildAnnotatedString {
                append("Paid by ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)) { append(draft.payer.name) }
            },
        )
        AnimatedVisibility(pendingCount > 0) {
            SectionHeader("Needs confirmation", pendingCount.toString(), warn = true)
        }
        draft.lines.forEach { line ->
            key(line.id) {
                AnimatedVisibility(visible = !line.confirmed) {
                    LineRow(line, draft, onToggleOwner, onConfirmLine)
                }
            }
        }
        AutoHeader(autoCount, autoExpanded, onToggleAuto)
        draft.lines.forEach { line ->
            key(line.id) {
                AnimatedVisibility(visible = line.confirmed && autoExpanded) {
                    LineRow(line, draft, onToggleOwner, onConfirmLine)
                }
            }
        }
        Totals(draft, pendingCount, onConfirmSplit)
    }
}

@Composable
private fun AutoHeader(count: Int, expanded: Boolean, onClick: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(200))
    Row(
        Modifier.fillMaxWidth().background(DivvyColors.Page).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Auto-assigned", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Dim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                },
            ) { n -> Text("$n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Dim) }
            Text("▼", Modifier.rotate(angle), fontSize = 10.sp, color = DivvyColors.Dim)
        }
    }
}

@Composable
private fun LineRow(
    line: ReceiptLine,
    draft: ReceiptDraft,
    onToggleOwner: (Int, String) -> Unit,
    onConfirmLine: (Int) -> Unit,
) {
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(line.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                Text(line.priceCents.toMoney(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
            }
            if (line.confirmed) {
                Text(line.ownersLabel(draft.people), fontSize = 12.sp, color = DivvyColors.Dim)
            } else {
                val pct = (line.confidence * 100).toInt()
                Text("Suggested ${line.ownersLabel(draft.people)}, $pct% sure", fontSize = 12.sp, color = DivvyColors.Warn)
            }
            Spacer(Modifier.height(9.dp))
            OwnerPicker(draft.people, line.owners, onToggle = { onToggleOwner(line.id, it) }) {
                if (!line.confirmed) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onConfirmLine(line.id) }) {
                        Text("Looks right", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
                    }
                }
            }
        }
        RowDivider()
    }
}

@Composable
private fun Totals(draft: ReceiptDraft, pendingCount: Int, onConfirmSplit: () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize().padding(20.dp)) {
        TotalLine("Subtotal", draft.subtotalCents.toMoney(), strong = false)
        TotalLine("Tax", draft.taxCents.toMoney(), strong = false)
        TotalLine("Total", draft.totalCents.toMoney(), strong = true)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            label = if (pendingCount == 0) "Confirm and split" else "$pendingCount left to confirm",
            enabled = pendingCount == 0,
            onClick = onConfirmSplit,
        )
    }
}

@Composable
private fun TotalLine(label: String, value: String, strong: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val color = if (strong) DivvyColors.Ink else DivvyColors.Dim
        val size = if (strong) 16.sp else 13.sp
        val weight = if (strong) FontWeight.Bold else FontWeight.Normal
        Text(label, fontSize = size, fontWeight = weight, color = color)
        Text(value, fontSize = size, fontWeight = weight, color = color)
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ReviewReceiptPreview() = DivvyTheme {
    ReviewReceiptScreen(SampleData.draft, autoExpanded = false, { _, _ -> }, {}, {}, {})
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ReviewReceiptAllConfirmedPreview() = DivvyTheme {
    ReviewReceiptScreen(SampleData.resolvedDraft, autoExpanded = true, { _, _ -> }, {}, {}, {})
}
