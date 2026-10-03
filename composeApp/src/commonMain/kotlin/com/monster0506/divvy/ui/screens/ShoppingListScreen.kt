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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ShoppingItem
import com.monster0506.divvy.ui.model.Suggestion
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

@Composable
fun ShoppingListScreen(
    items: List<ShoppingItem>,
    suggestions: List<Suggestion>,
    onToggle: (index: Int) -> Unit,
    onAddSuggestion: (index: Int) -> Unit,
    onTab: (Tab) -> Unit = {},
) {
    AppScaffold(selected = Tab.Shopping, onSelect = onTab) {
        ScreenHeader("Shopping list")
        items.forEachIndexed { i, item ->
            key(item.name) {
                val enter = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(enter) { ItemRow(item) { onToggle(i) } }
            }
        }
        AnimatedVisibility(suggestions.any { !it.added }) { SectionHeader("You may also need") }
        suggestions.forEachIndexed { i, s ->
            key(s.name) { AnimatedVisibility(!s.added) { SuggestionRow(s) { onAddSuggestion(i) } } }
        }
    }
}

@Composable
private fun ItemRow(item: ShoppingItem, onToggle: () -> Unit) {
    val fill by animateColorAsState(if (item.done) DivvyColors.Accent else Color.Transparent, tween(180))
    val edge by animateColorAsState(if (item.done) DivvyColors.Accent else Color(0xFF9AA1A8), tween(180))
    val tick by animateFloatAsState(if (item.done) 1f else 0f, tween(150))
    val text by animateColorAsState(if (item.done) DivvyColors.Dim else DivvyColors.Ink, tween(180))
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shape = RoundedCornerShape(5.dp)
            Box(
                Modifier.size(20.dp).clip(shape).background(fill).border(1.5.dp, edge, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", Modifier.scale(tick).alpha(tick), fontSize = 13.sp, color = Color.White)
            }
            Text(
                item.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = text,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
            )
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

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ShoppingListPreview() = DivvyTheme {
    ShoppingListScreen(SampleData.shopping, SampleData.suggestions, {}, {})
}
