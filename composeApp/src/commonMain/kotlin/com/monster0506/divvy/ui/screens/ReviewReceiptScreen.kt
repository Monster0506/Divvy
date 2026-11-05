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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.domain.centsToInput
import com.monster0506.divvy.domain.eachCents
import com.monster0506.divvy.domain.edited
import com.monster0506.divvy.domain.formatQuantity
import com.monster0506.divvy.domain.parseDateInput
import com.monster0506.divvy.domain.parsePriceCents
import com.monster0506.divvy.domain.totalFor
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Tapping a line's name or price opens it in place for typing; the ×N pill opens a quantity stepper whose − becomes
 * delete at one; swiping a line either way deletes it, with an undo bar at the bottom.
 */
@Composable
fun ReviewReceiptScreen(
    draft: ReceiptDraft,
    autoExpanded: Boolean,
    onToggleOwner: (lineId: Int, personId: String) -> Unit,
    onConfirmLine: (lineId: Int) -> Unit,
    onToggleAuto: () -> Unit,
    onConfirmSplit: () -> Unit,
    onTab: (Tab) -> Unit = {},
    onEditLine: (lineId: Int, name: String, quantity: Double, eachCents: Int) -> Unit = { _, _, _, _ -> },
    onAddLine: (name: String, quantity: Double, eachCents: Int) -> Unit = { _, _, _ -> },
    onDeleteLine: (lineId: Int) -> Unit = {},
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    notice: String? = null,
    onChoosePayer: ((personId: String) -> Unit)? = null,
    onEditDetails: ((String, String, Int, Int) -> Unit)? = null,
) {
    var choosingPayer by remember { mutableStateOf(false) }
    var editingDetails by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<LineEditor?>(null) }
    // The line whose ×N pill is open as a stepper; [stepTouches] restarts its close timer.
    var stepping by remember { mutableStateOf<Int?>(null) }
    var stepTouches by remember { mutableIntStateOf(0) }
    // Stepper taps wait here briefly so a run of taps is saved once.
    var pendingQuantity by remember { mutableStateOf(emptyMap<Int, Double>()) }
    var deletedName by remember { mutableStateOf("") }
    var undoShown by remember { mutableStateOf(false) }
    var deletions by remember { mutableIntStateOf(0) }
    val currentDraft by rememberUpdatedState(draft)
    val editLine by rememberUpdatedState(onEditLine)
    val addLine by rememberUpdatedState(onAddLine)

    fun saveQuantities() {
        pendingQuantity.forEach { (id, quantity) ->
            val line = currentDraft.lines.find { it.id == id } ?: return@forEach
            if (quantity != line.quantity) editLine(id, line.name, quantity, line.eachCents)
        }
        pendingQuantity = emptyMap()
    }

    /** Saves what was typed. A blank name or an unreadable price leaves the line as it was. */
    fun save(typed: LineEditor) {
        val name = typed.name.text.trim()
        val cents = parsePriceCents(typed.price.text) ?: return
        if (name.isEmpty()) return
        if (typed.lineId == null) return addLine(name, typed.quantity, cents)
        val line = currentDraft.lines.find { it.id == typed.lineId } ?: return
        if (name != line.name || typed.quantity != line.quantity || cents != line.eachCents) {
            editLine(line.id, name, typed.quantity, cents)
        }
    }

    fun openEditor(next: LineEditor?) {
        editor?.let(::save)
        editor = next
        stepping = null
    }

    fun tapLine(line: ReceiptLine, field: LineField) {
        val quantity = pendingQuantity[line.id] ?: line.quantity
        pendingQuantity = pendingQuantity - line.id
        openEditor(LineEditor.of(line, quantity, field))
    }

    fun delete(line: ReceiptLine) {
        if (editor?.lineId == line.id) editor = null
        stepping = null
        pendingQuantity = pendingQuantity - line.id
        deletedName = line.name
        undoShown = true
        deletions++
        onDeleteLine(line.id)
    }

    fun step(line: ReceiptLine, quantity: Double) {
        pendingQuantity = pendingQuantity + (line.id to quantity)
        stepTouches++
    }

    LaunchedEffect(pendingQuantity) {
        if (pendingQuantity.isEmpty()) return@LaunchedEffect
        delay(600)
        saveQuantities()
    }
    LaunchedEffect(stepping, stepTouches) {
        if (stepping == null) return@LaunchedEffect
        delay(2_600)
        stepping = null
    }
    LaunchedEffect(deletions) {
        if (!undoShown) return@LaunchedEffect
        delay(6_000)
        undoShown = false
    }
    // Leaving the screen keeps what was typed or stepped instead of dropping it.
    DisposableEffect(Unit) {
        onDispose {
            editor?.let(::save)
            saveQuantities()
        }
    }

    val pendingCount = draft.lines.count { !it.confirmed }
    val autoCount = draft.lines.size - pendingCount
    val lineRow: @Composable (ReceiptLine) -> Unit = { saved ->
        val line = pendingQuantity[saved.id]?.let { saved.edited(saved.name, it, saved.eachCents) } ?: saved
        val open = editor?.takeIf { it.lineId == line.id }
        if (open != null) {
            LineEditorRow(
                editor = open,
                line = line,
                onChange = { editor = it },
                onDone = { if (open.priceIsReadable) openEditor(null) },
                onDelete = { delete(line) },
            ) {
                OwnerPicker(draft.people, line.owners, onToggle = { onToggleOwner(line.id, it) }) {
                    ConfirmButton(line, onConfirmLine)
                }
            }
        } else {
            SwipeToDelete(onDelete = { delete(line) }) {
                LineRow(
                    line = line,
                    draft = draft,
                    stepping = stepping == line.id,
                    onName = { tapLine(saved, LineField.Name) },
                    onPrice = { tapLine(saved, LineField.Price) },
                    onQuantity = { openEditor(null); stepping = line.id; stepTouches++ },
                    onFewer = { if (line.quantity <= 1.0) delete(line) else step(saved, line.quantity - 1) },
                    onMore = { step(saved, line.quantity + 1) },
                    onToggleOwner = onToggleOwner,
                    onConfirmLine = onConfirmLine,
                )
            }
        }
    }
    AppScaffold(
        selected = Tab.Receipts,
        onSelect = onTab,
        bottomBar = {
            AnimatedVisibility(canUndo && undoShown) {
                DeletedBanner(deletedName, onUndo = { onUndo(); undoShown = false })
            }
        },
    ) {
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
            action = if (onEditDetails != null) ({ TextButton(onClick = { editingDetails = true }) { Text("Edit") } }) else null,
        )
        if (notice != null) SectionHeader(notice, warn = true)
        AnimatedVisibility(pendingCount > 0) {
            SectionHeader("Needs confirmation", pendingCount.toString(), warn = true)
        }
        draft.lines.forEach { line ->
            key(line.id) {
                AnimatedVisibility(visible = !line.confirmed) { lineRow(line) }
            }
        }
        AutoHeader(autoCount, autoExpanded, onToggleAuto)
        draft.lines.forEach { line ->
            key(line.id) {
                AnimatedVisibility(visible = line.confirmed && autoExpanded) { lineRow(line) }
            }
        }
        val adding = editor?.takeIf { it.lineId == null }
        if (adding != null) {
            LineEditorRow(
                editor = adding,
                line = null,
                onChange = { editor = it },
                onDone = { if (adding.priceIsReadable) openEditor(null) },
                onDelete = { editor = null },
            )
        } else {
            AddLineButton(onClick = { openEditor(LineEditor.Blank) })
        }
        Totals(
            draft,
            pendingCount,
            onConfirmSplit = { openEditor(null); saveQuantities(); onConfirmSplit() },
            needsPayer = onChoosePayer != null && !draft.payerChosen,
        )
    }
    if (choosingPayer && onChoosePayer != null) {
        PayerDialog(
            draft = draft,
            onDismiss = { choosingPayer = false },
            onPick = { onChoosePayer(it); choosingPayer = false },
        )
    }
    if (editingDetails && onEditDetails != null) {
        ReceiptDetailsDialog(draft, onDismiss = { editingDetails = false }, onSave = { store, date, tax, total ->
            onEditDetails(store, date, tax, total)
            editingDetails = false
        })
    }
}

private enum class LineField { Name, Price }

/** What is being typed into an open line; a null [lineId] is a line being added. */
private data class LineEditor(
    val lineId: Int?,
    val name: TextFieldValue,
    val price: TextFieldValue,
    val quantity: Double,
    val focus: LineField,
) {
    /** An empty price is allowed (it discards the edit); anything else must be an amount. */
    val priceIsReadable get() = price.text.isBlank() || parsePriceCents(price.text) != null

    companion object {
        val Blank = LineEditor(null, TextFieldValue(), TextFieldValue(), 1.0, LineField.Name)

        /** Opens [line] with the cursor after its name, or with its price selected so typing replaces it. */
        fun of(line: ReceiptLine, quantity: Double, focus: LineField): LineEditor {
            val price = centsToInput(line.eachCents)
            val priceSelection = if (focus == LineField.Price) TextRange(0, price.length) else TextRange(price.length)
            return LineEditor(
                line.id,
                TextFieldValue(line.name, TextRange(line.name.length)),
                TextFieldValue(price, priceSelection),
                quantity,
                focus,
            )
        }
    }
}

@Composable
private fun ReceiptDetailsDialog(draft: ReceiptDraft, onDismiss: () -> Unit, onSave: (String, String, Int, Int) -> Unit) {
    var store by remember { mutableStateOf(draft.store) }
    var date by remember { mutableStateOf(draft.dateIso ?: "") }
    var tax by remember { mutableStateOf(centsToInput(draft.taxCents)) }
    var total by remember { mutableStateOf(centsToInput(draft.printedTotalCents ?: draft.totalCents)) }
    val parsedDate = parseDateInput(date)
    val taxCents = parsePriceCents(tax)
    val totalCents = parsePriceCents(total)
    val canSave = store.isNotBlank() && parsedDate != null && taxCents != null && totalCents != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit receipt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(store, { store = it }, label = { Text("Store") }, singleLine = true,
                    isError = store.isNotBlank().not())
                OutlinedTextField(date, { date = it }, label = { Text("Date (M/D/YYYY)") }, singleLine = true,
                    isError = date.isNotBlank() && parsedDate == null)
                OutlinedTextField(tax, { tax = it }, label = { Text("Tax") }, singleLine = true,
                    isError = tax.isNotBlank() && taxCents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(total, { total = it }, label = { Text("Total") }, singleLine = true,
                    isError = total.isNotBlank() && totalCents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (store.isBlank()) Text("Enter a store name.", color = DivvyColors.Warn, fontSize = 12.sp)
                if (date.isNotBlank() && parsedDate == null) Text("Enter a real date, such as 9/18/2026.", color = DivvyColors.Warn, fontSize = 12.sp)
                if (tax.isNotBlank() && taxCents == null) Text("Enter a valid tax amount.", color = DivvyColors.Warn, fontSize = 12.sp)
                if (total.isNotBlank() && totalCents == null) Text("Enter a valid total amount.", color = DivvyColors.Warn, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(store.trim(), parsedDate!!, taxCents!!, totalCents!!) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun DeletedBanner(name: String, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DivvyColors.Page).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Deleted $name", Modifier.weight(1f), fontSize = 13.sp, color = DivvyColors.Ink, maxLines = 1)
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}

@Composable
private fun AddLineButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .drawBehind {
                drawRoundRect(
                    color = DivvyColors.Outline,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            }
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ Add item", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
    }
}

/** Swiping the line either way deletes it; screen readers get a Delete action instead. */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        onDismiss = { onDelete() },
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
        },
        backgroundContent = {
            val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val armed = swipe.targetValue != SwipeToDismissBoxValue.Settled
            Box(
                Modifier.fillMaxSize().background(DivvyColors.Warn.copy(alpha = if (armed) 1f else 0.7f)).padding(horizontal = 20.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text("Delete", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        },
    ) {
        Box(Modifier.background(DivvyColors.Surface)) { content() }
    }
}

@Composable
private fun QuantityPill(quantity: Double, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).border(1.dp, DivvyColors.Outline, CircleShape)
            .clickable(onClickLabel = "Change quantity", onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text("×${formatQuantity(quantity)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DivvyColors.Ink)
    }
}

/** At one or less, − deletes the line. */
@Composable
private fun QuantityStepper(quantity: Double, onFewer: () -> Unit, onMore: () -> Unit, compact: Boolean = false) {
    val side = if (compact) 30.dp else 36.dp
    Row(
        Modifier.height(side).clip(CircleShape).border(1.dp, DivvyColors.Outline, CircleShape).background(DivvyColors.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val last = quantity <= 1.0
        Box(
            Modifier.size(side).clickable(onClickLabel = if (last) "Delete item" else "One fewer", onClick = onFewer),
            contentAlignment = Alignment.Center,
        ) {
            if (last) Icon(DivvyIcons.Delete, contentDescription = "Delete item", Modifier.size(17.dp), tint = DivvyColors.Warn)
            else Text("−", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
        }
        Text(
            formatQuantity(quantity),
            Modifier.widthIn(min = 18.dp),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = DivvyColors.Ink,
        )
        Box(Modifier.size(side).clickable(onClickLabel = "One more", onClick = onMore), contentAlignment = Alignment.Center) {
            Text("+", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, Modifier.padding(bottom = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Dim)
}

/** A line open for typing. [line] is null for a new line, which has no owners yet. */
@Composable
private fun LineEditorRow(
    editor: LineEditor,
    line: ReceiptLine?,
    onChange: (LineEditor) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    owners: (@Composable () -> Unit)? = null,
) {
    val nameFocus = remember { FocusRequester() }
    val priceFocus = remember { FocusRequester() }
    LaunchedEffect(editor.lineId) {
        (if (editor.focus == LineField.Name) nameFocus else priceFocus).requestFocus()
    }
    val cents = parsePriceCents(editor.price.text)
    val discount = line?.discountCents ?: 0
    val total = cents?.let { line?.totalFor(editor.quantity, it) ?: (it * editor.quantity).roundToInt() }
    Column(
        Modifier.fillMaxWidth().background(DivvyColors.Page.copy(alpha = 0.45f))
            .drawBehind { drawRect(DivvyColors.Accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = editor.name,
            onValueChange = { onChange(editor.copy(name = it)) },
            placeholder = { Text("Item name") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = {
                onChange(editor.copy(price = editor.price.copy(selection = TextRange(0, editor.price.text.length))))
                priceFocus.requestFocus()
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = editor.price,
                onValueChange = { onChange(editor.copy(price = it)) },
                label = { Text(if (editor.quantity == 1.0 && discount == 0) "Price" else "Price each") },
                placeholder = { Text("0.00") },
                singleLine = true,
                isError = !editor.priceIsReadable,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.weight(1f).focusRequester(priceFocus),
            )
            Column(Modifier.padding(bottom = 10.dp)) {
                FieldLabel("Qty")
                QuantityStepper(
                    editor.quantity,
                    onFewer = { if (editor.quantity <= 1.0) onDelete() else onChange(editor.copy(quantity = editor.quantity - 1)) },
                    onMore = { onChange(editor.copy(quantity = editor.quantity + 1)) },
                )
            }
            Column(Modifier.padding(bottom = 10.dp), horizontalAlignment = Alignment.End) {
                FieldLabel("Total")
                Text(
                    total?.toMoney() ?: "—",
                    Modifier.padding(vertical = 6.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = DivvyColors.Ink,
                )
            }
        }
        if (discount != 0) {
            Text("After a ${discount.toMoney()} discount", fontSize = 12.sp, color = DivvyColors.Dim)
        }
        if (owners != null) {
            Spacer(Modifier.height(10.dp))
            owners()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) {
                Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        }
    }
    RowDivider()
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
            Icon(DivvyIcons.ChevronDown, contentDescription = null, modifier = Modifier.rotate(angle), tint = DivvyColors.Dim)
        }
    }
}

@Composable
private fun LineRow(
    line: ReceiptLine,
    draft: ReceiptDraft,
    stepping: Boolean,
    onName: () -> Unit,
    onPrice: () -> Unit,
    onQuantity: () -> Unit,
    onFewer: () -> Unit,
    onMore: () -> Unit,
    onToggleOwner: (Int, String) -> Unit,
    onConfirmLine: (Int) -> Unit,
) {
    Column {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line.name,
                    Modifier.weight(1f).clickable(onClickLabel = "Edit name", onClick = onName),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DivvyColors.Ink,
                )
                Spacer(Modifier.size(8.dp))
                if (stepping) QuantityStepper(line.quantity, onFewer, onMore, compact = true)
                else QuantityPill(line.quantity, onQuantity)
                Text(
                    line.priceCents.toMoney(),
                    Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Edit price", onClick = onPrice)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DivvyColors.Ink,
                )
            }
            if (line.confirmed) {
                Text(line.ownersLabel(draft.people), fontSize = 12.sp, color = DivvyColors.Dim)
            } else if (line.owners.isEmpty()) {
                Text("Choose who purchased this item", fontSize = 12.sp, color = DivvyColors.Warn)
            } else if (line.confidence == 1f) {
                Text("Selected ${line.ownersLabel(draft.people)}", fontSize = 12.sp, color = DivvyColors.Warn)
            } else {
                val pct = (line.confidence * 100).toInt()
                Text("Suggested ${line.ownersLabel(draft.people)}, $pct% sure", fontSize = 12.sp, color = DivvyColors.Warn)
            }
            Spacer(Modifier.height(9.dp))
            OwnerPicker(draft.people, line.owners, onToggle = { onToggleOwner(line.id, it) }) {
                ConfirmButton(line, onConfirmLine)
            }
        }
        RowDivider()
    }
}

@Composable
private fun RowScope.ConfirmButton(line: ReceiptLine, onConfirmLine: (Int) -> Unit) {
    if (line.confirmed) return
    Spacer(Modifier.weight(1f))
    TextButton(onClick = { onConfirmLine(line.id) }, enabled = line.owners.isNotEmpty()) {
        Text("Confirm", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
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
