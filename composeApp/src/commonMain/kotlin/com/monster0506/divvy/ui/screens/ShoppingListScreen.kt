package com.monster0506.divvy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.shopping.ListPhase
import com.monster0506.divvy.shopping.ShoppingListState
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.DivvyIcons
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ShoppingItem
import com.monster0506.divvy.ui.model.Suggestion
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

/**
 * The household's grocery list. Type to add (with names the household has bought before offered as
 * you go), tick the box to check an item off, tap an item for its details, or swipe it left to remove it.
 * Pull down to load other people's changes now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    state: ShoppingListState,
    suggestions: List<Suggestion>,
    onInput: (String) -> Unit,
    onAdd: (String) -> Unit,
    onToggle: (id: String) -> Unit,
    onOpen: (id: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onUndoRemove: () -> Unit,
    onAddSuggestion: (index: Int) -> Unit,
    onRefresh: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
    onTab: (Tab) -> Unit = {},
) {
    var showPurchased by rememberSaveable { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
        AppScaffold(selected = Tab.Shopping, onSelect = onTab) {
            val toBuy = state.open.size
            ScreenHeader("Shopping list", metaStart = if (state.phase == ListPhase.Ready) "$toBuy to buy" else null)
            AddField(state.input, state.adding, onInput) { onAdd(state.input) }
            state.suggestions.forEach { name -> AutocompleteRow(name) { onAdd(name) } }
            state.message?.let { SectionHeader(it, "Dismiss", warn = true, onClick = onDismissMessage) }
            state.removed?.let { RemovedBanner(it, onUndoRemove) }

            when (state.phase) {
                ListPhase.Idle, ListPhase.Loading ->
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ListPhase.Failed -> {
                    ListNote("Could not load the list.")
                    TextButton(onClick = onRefresh, Modifier.padding(horizontal = 12.dp)) { Text("Try again") }
                }
                ListPhase.Ready -> {
                    if (state.items.isEmpty()) ListNote("Nothing on the list. Add something above.")
                    state.open.forEach { item ->
                        key(item.id) {
                            val enter = remember { MutableTransitionState(false).apply { targetState = true } }
                            AnimatedVisibility(enter) {
                                SwipeToRemove(onRemove = { onRemove(item.id) }) {
                                    ItemRow(item, onToggle = { onToggle(item.id) }, onOpen = { onOpen(item.id) })
                                }
                            }
                        }
                    }
                    val bought = state.purchased
                    if (bought.isNotEmpty()) {
                        SectionHeader("Purchased (${bought.size})", if (showPurchased) "Hide" else "Show") {
                            showPurchased = !showPurchased
                        }
                        AnimatedVisibility(showPurchased) {
                            Column {
                                bought.forEach { item ->
                                    key(item.id) {
                                        SwipeToRemove(onRemove = { onRemove(item.id) }) {
                                            ItemRow(item, onToggle = { onToggle(item.id) }, onOpen = { onOpen(item.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(suggestions.any { !it.added }) { SectionHeader("You may also need") }
            suggestions.forEachIndexed { i, s ->
                key(s.name) { AnimatedVisibility(!s.added) { SuggestionRow(s) { onAddSuggestion(i) } } }
            }
        }
    }
}

@Composable
private fun AddField(value: String, adding: Boolean, onChange: (String) -> Unit, onAdd: () -> Unit) {
    val canAdd = value.isNotBlank() && !adding
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("Add an item") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAdd, enabled = canAdd) {
            Text("Add", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (canAdd) DivvyColors.Accent else DivvyColors.Dim)
        }
    }
}

/** A name the household has bought before; tapping it adds it. */
@Composable
private fun AutocompleteRow(name: String, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), fontSize = 14.sp, color = DivvyColors.Ink)
        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
    }
    RowDivider()
}

@Composable
private fun RemovedBanner(item: ShoppingItem, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DivvyColors.Page).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Removed ${item.name}", Modifier.weight(1f), fontSize = 13.sp, color = DivvyColors.Ink)
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}

/** Swiping the row right to left removes it. */
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(DivvyColors.Warn).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Remove", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        },
    ) {
        Box(Modifier.background(DivvyColors.Surface)) { content() }
    }
}

@Composable
private fun ItemRow(item: ShoppingItem, onToggle: () -> Unit, onOpen: () -> Unit) {
    val fill by animateColorAsState(if (item.done) DivvyColors.Accent else Color.Transparent, tween(180))
    val edge by animateColorAsState(if (item.done) DivvyColors.Accent else Color(0xFF9AA1A8), tween(180))
    val tick by animateFloatAsState(if (item.done) 1f else 0f, tween(150))
    val text by animateColorAsState(if (item.done) DivvyColors.Dim else DivvyColors.Ink, tween(180))
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The box has its own tap target so checking an item off never opens it.
            Box(Modifier.clickable(onClick = onToggle).padding(start = 20.dp, end = 12.dp, top = 13.dp, bottom = 13.dp)) {
                val shape = RoundedCornerShape(5.dp)
                Box(
                    Modifier.size(20.dp).clip(shape).background(fill).border(1.5.dp, edge, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DivvyIcons.Check, contentDescription = if (item.done) "Bought" else "Not bought", modifier = Modifier.size(15.dp).scale(tick).alpha(tick), tint = Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = text,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.notes?.let { Text(it, fontSize = 12.sp, color = DivvyColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item.quantity?.let { Text(it, Modifier.padding(start = 8.dp), fontSize = 13.sp, color = DivvyColors.Dim) }
        }
        RowDivider()
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onAdd: () -> Unit) {
    Column {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(suggestion.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
            Text(suggestion.reason, fontSize = 12.sp, color = DivvyColors.Dim)
        }
        OutlinedButton(
            onClick = onAdd,
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DivvyColors.Ink),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
        }
    }
    RowDivider()
    }
}

@Composable
private fun ListNote(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), fontSize = 14.sp, color = DivvyColors.Dim)
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ShoppingListPreview() = DivvyTheme {
    ShoppingListScreen(
        state = ShoppingListState(phase = ListPhase.Ready, items = SampleData.shopping, input = "eg", suggestions = listOf("Large eggs")),
        suggestions = SampleData.suggestions,
        onInput = {}, onAdd = {}, onToggle = {}, onOpen = {}, onRemove = {}, onUndoRemove = {}, onAddSuggestion = {},
    )
}
