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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.domain.centsToInput
import com.monster0506.divvy.domain.parsePriceCents
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.DivvyIcons
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
    onEditLine: (lineId: Int, name: String, priceCents: Int) -> Unit = { _, _, _ -> },
    onAddLine: (name: String, priceCents: Int) -> Unit = { _, _ -> },
    onDeleteLine: (lineId: Int) -> Unit = {},
    onMergeLines: (Set<Int>) -> Unit = {},
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    notice: String? = null,
    onChoosePayer: ((personId: String) -> Unit)? = null,
    onGuessAgain: (() -> Unit)? = null,
    guessing: Boolean = false,
) {
    var choosingPayer by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReceiptLine?>(null) }
    var adding by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    val onLineClick = { line: ReceiptLine ->
        if (merging) selected = if (line.id in selected) selected - line.id else selected + line.id
        else editing = line
    }
    val pendingCount = draft.lines.count { !it.confirmed }
    val autoCount = draft.lines.size - pendingCount
    AppScaffold(selected = Tab.Receipts, onSelect = onTab) {
        ScreenHeader(
            title = draft.store,
            metaStart = draft.date,
            metaEnd = buildAnnotatedString {
                append("Paid by ")
                val named = draft.payerChosen || onChoosePayer == null
                val look = SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    color = if (named) DivvyColors.Ink else DivvyColors.Warn,
                    textDecoration = if (onChoosePayer != null) TextDecoration.Underline else null,
                )
                withStyle(look) { append(if (named) draft.payer.name else "choose") }
            },
            onMetaEndClick = onChoosePayer?.let { { choosingPayer = true } },
        )
        if (notice != null) SectionHeader(notice, warn = true)
        EditBar(
            merging = merging,
            selectedCount = selected.size,
            canUndo = canUndo,
            onAdd = { adding = true },
            onToggleMerge = { merging = !merging; selected = emptySet() },
            onMerge = { onMergeLines(selected); merging = false; selected = emptySet() },
            onUndo = onUndo,
            onGuessAgain = onGuessAgain,
            guessing = guessing,
        )
        AnimatedVisibility(pendingCount > 0) {
            SectionHeader("Needs confirmation", pendingCount.toString(), warn = true)
        }
        draft.lines.forEach { line ->
            key(line.id) {
                AnimatedVisibility(visible = !line.confirmed) {
                    LineRow(line, draft, line.id in selected, { onLineClick(line) }, onToggleOwner, onConfirmLine)
                }
            }
        }
        AutoHeader(autoCount, autoExpanded, onToggleAuto)
        draft.lines.forEach { line ->
            key(line.id) {
                AnimatedVisibility(visible = line.confirmed && autoExpanded) {
                    LineRow(line, draft, line.id in selected, { onLineClick(line) }, onToggleOwner, onConfirmLine)
                }
            }
        }
        Totals(draft, pendingCount, onConfirmSplit, needsPayer = onChoosePayer != null && !draft.payerChosen)
    }
    if (choosingPayer && onChoosePayer != null) {
        PayerDialog(
            draft = draft,
            onDismiss = { choosingPayer = false },
            onPick = { onChoosePayer(it); choosingPayer = false },
        )
    }
    editing?.let { line ->
        LineDialog(
            title = "Edit item",
            initialName = line.name,
            initialPrice = centsToInput(line.priceCents),
            onDismiss = { editing = null },
            onSave = { name, cents -> onEditLine(line.id, name, cents); editing = null },
            onDelete = { onDeleteLine(line.id); editing = null },
        )
    }
    if (adding) {
        LineDialog(
            title = "Add item",
            initialName = "",
            initialPrice = "",
            onDismiss = { adding = false },
            onSave = { name, cents -> onAddLine(name, cents); adding = false },
            onDelete = null,
        )
    }
}

@Composable
private fun PayerDialog(draft: ReceiptDraft, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who paid?") },
        text = {
            Column {
                draft.people.forEach { person ->
                    val current = draft.payerChosen && person.id == draft.payer.id
                    Text(
                        if (current) "${person.name} (selected)" else person.name,
                        fontSize = 16.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        color = DivvyColors.Ink,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(person.id) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditBar(
    merging: Boolean,
    selectedCount: Int,
    canUndo: Boolean,
    onAdd: () -> Unit,
    onToggleMerge: () -> Unit,
    onMerge: () -> Unit,
    onUndo: () -> Unit,
    onGuessAgain: (() -> Unit)?,
    guessing: Boolean,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (merging) {
            TextButton(onClick = onMerge, enabled = selectedCount >= 2) {
                Text("Merge $selectedCount selected", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onToggleMerge) { Text("Cancel", fontSize = 13.sp, color = DivvyColors.Dim) }
        } else {
            TextButton(onClick = onAdd) { Text("Add item", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onToggleMerge) { Text("Merge", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        if (onGuessAgain != null) {
                TextButton(onClick = onGuessAgain, enabled = !guessing) {
                    Text(if (guessing) "Guessing..." else "Guess owners again", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (canUndo) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onUndo) { Text("Undo delete", fontSize = 13.sp, color = DivvyColors.Accent) }
            }
        }
    }
}

@Composable
private fun LineDialog(
    title: String,
    initialName: String,
    initialPrice: String,
    onDismiss: () -> Unit,
    onSave: (name: String, priceCents: Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initialName) }
    var price by remember { mutableStateOf(initialPrice) }
    val cents = parsePriceCents(price)
    val canSave = cents != null && name.isNotBlank()
    val save = { onSave(name.trim(), cents!!) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(
                    price,
                    { price = it },
                    label = { Text("Price") },
                    singleLine = true,
                    isError = price.isNotEmpty() && cents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = canSave) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = DivvyColors.Warn) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
            Icon(
                DivvyIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.rotate(angle),
                tint = DivvyColors.Dim
            )
        }
    }
}

@Composable
private fun LineRow(
    line: ReceiptLine,
    draft: ReceiptDraft,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleOwner: (Int, String) -> Unit,
    onConfirmLine: (Int) -> Unit,
) {
    Column {
        Column(
            Modifier.fillMaxWidth().background(if (selected) DivvyColors.Page else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(line.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                Text(
                    line.priceCents.toMoney(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DivvyColors.Ink
                )
            }
            if (line.confirmed) {
                Text(line.ownersLabel(draft.people), fontSize = 12.sp, color = DivvyColors.Dim)
            } else {
                val pct = (line.confidence * 100).toInt()
                Text(
                    "Suggested ${line.ownersLabel(draft.people)}, $pct% sure",
                    fontSize = 12.sp,
                    color = DivvyColors.Warn
                )
            }
            Spacer(Modifier.height(9.dp))
            OwnerPicker(draft.people, line.owners, onToggle = { onToggleOwner(line.id, it) }) {
                if (!line.confirmed) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onConfirmLine(line.id) }) {
                        Text(
                            "Looks right",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DivvyColors.Accent
                        )
                    }
                }
            }
        }
        RowDivider()
    }
}

@Composable
private fun Totals(draft: ReceiptDraft, pendingCount: Int, onConfirmSplit: () -> Unit, needsPayer: Boolean) {
    Column(Modifier.fillMaxWidth().animateContentSize().padding(20.dp)) {
        TotalLine("Subtotal", draft.subtotalCents.toMoney(), strong = false)
        TotalLine("Tax", draft.taxCents.toMoney(), strong = false)
        TotalLine("Total", draft.totalCents.toMoney(), strong = true)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            label = when {
                needsPayer -> "Choose who paid"
                pendingCount == 0 -> "Confirm and split"
                else -> "$pendingCount left to confirm"
            },
            enabled = pendingCount == 0 && !needsPayer,
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
