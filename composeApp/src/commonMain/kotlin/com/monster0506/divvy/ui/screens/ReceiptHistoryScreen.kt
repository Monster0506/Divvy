package com.monster0506.divvy.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.domain.isoDateFromUtcMillis
import com.monster0506.divvy.domain.utcMillisFromIso
import com.monster0506.divvy.review.formatDate
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.HistoryEntry
import com.monster0506.divvy.ui.model.ReceiptStatus
import com.monster0506.divvy.ui.model.toMoney
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme
import kotlinx.coroutines.flow.first

/** Everything the list needs for search, filters and paging. Pass null where the list is not on a server. */
class HistoryFilters(
    val store: String,
    val status: ReceiptStatusDto?,
    /** The date limits as `YYYY-MM-DD`, or null for no limit. */
    val fromIso: String?,
    val toIso: String?,
    val active: Boolean,
    val hasMore: Boolean,
    val loadingMore: Boolean,
    /** True when the last request failed, so scrolling does not keep retrying on its own. */
    val loadFailed: Boolean,
    val onStore: (String) -> Unit,
    val onStatus: (ReceiptStatusDto?) -> Unit,
    val onFrom: (String?) -> Unit,
    val onTo: (String?) -> Unit,
    val onClear: () -> Unit,
    val onLoadMore: () -> Unit,
)

/** How close to the end of the list, in pixels, the next page starts loading. */
private const val LOAD_MORE_DISTANCE_PX = 900

private val statusChoices = listOf(
    "Needs review" to ReceiptStatusDto.Draft,
    "Confirmed" to ReceiptStatusDto.Confirmed,
    "Failed" to ReceiptStatusDto.Failed,
    "Voided" to ReceiptStatusDto.Void,
)

@Composable
fun ReceiptHistoryScreen(
    entries: List<HistoryEntry>,
    onOpen: (index: Int) -> Unit,
    onScan: () -> Unit = {},
    onTab: (Tab) -> Unit = {},
    message: String? = null,
    scrollState: ScrollState = rememberScrollState(),
    filters: HistoryFilters? = null,
) {
    AppScaffold(selected = Tab.Receipts, onSelect = onTab, scrollState = scrollState) {
        ScreenHeader("Receipts", metaStart = "Newest first", action = {
            TextButton(onClick = onScan) {
                Text("Scan receipt", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        })
        if (filters != null) FilterBar(filters)
        if (message != null) SectionHeader(message, warn = true)
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
        if (filters != null) {
            if (filters.loadingMore) {
                SectionHeader("Loading more receipts...")
            } else if (filters.hasMore && filters.loadFailed) {
                TextButton(onClick = filters.onLoadMore, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
                }
            }
        }
    }
    if (filters != null) {
        LaunchedEffect(scrollState, filters.hasMore, filters.loadingMore, filters.loadFailed, entries.size) {
            if (!filters.hasMore || filters.loadingMore || filters.loadFailed) return@LaunchedEffect
            // Before the list is measured the scroll range is huge, so this waits until the end is close.
            snapshotFlow { scrollState.maxValue - scrollState.value <= LOAD_MORE_DISTANCE_PX }.first { it }
            filters.onLoadMore()
        }
    }
}

private enum class DateField { From, To }

@Composable
private fun FilterBar(f: HistoryFilters) {
    var picking by remember { mutableStateOf<DateField?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = f.store,
            onValueChange = f.onStore,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search stores") },
            placeholder = { Text("Part of a name, or * for anything") },
            singleLine = true,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("All", f.status == null) { f.onStatus(null) }
            statusChoices.forEach { (label, status) ->
                StatusChip(label, f.status == status) { f.onStatus(status) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateButton("From", f.fromIso, Modifier.weight(1f)) { picking = DateField.From }
            DateButton("To", f.toIso, Modifier.weight(1f)) { picking = DateField.To }
        }
        if (f.active) {
            TextButton(onClick = f.onClear) {
                Text("Clear filters", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        }
    }
    picking?.let { field ->
        DateChoiceDialog(
            initialIso = if (field == DateField.From) f.fromIso else f.toIso,
            onPick = { iso ->
                if (field == DateField.From) f.onFrom(iso) else f.onTo(iso)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DateButton(label: String, iso: String?, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            if (iso == null) label else "$label ${formatDate(iso)}",
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = DivvyColors.Ink,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChoiceDialog(initialIso: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialIso?.let(::utcMillisFromIso))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(pickerState.selectedDateMillis?.let(::isoDateFromUtcMillis)) }) { Text("OK") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = DivvyColors.Ink,
        modifier = Modifier.clip(shape)
            .background(if (selected) DivvyColors.Page else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) DivvyColors.Ink else DivvyColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ReceiptHistoryPreview() = DivvyTheme {
    ReceiptHistoryScreen(SampleData.history, {})
}