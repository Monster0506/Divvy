package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.review.formatDate
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.ShoppingItem
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

/**
 * One grocery list item. Done saves any edits and goes back; checking it off applies at once.
 * Remove asks first, then takes it off the list.
 */
@Composable
fun ShoppingItemScreen(
    item: ShoppingItem,
    saving: Boolean,
    onSave: (name: String, quantity: String, notes: String) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onTab: (Tab) -> Unit = {},
    message: String? = null,
) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var quantity by remember(item.id) { mutableStateOf(item.quantity.orEmpty()) }
    var notes by remember(item.id) { mutableStateOf(item.notes.orEmpty()) }
    var confirmingRemove by remember { mutableStateOf(false) }

    AppScaffold(selected = Tab.Shopping, onSelect = onTab) {
        ScreenHeader(item.name, action = {
            TextButton(onClick = { onSave(name, quantity, notes) }, enabled = !saving && name.isNotBlank()) {
                Text(if (saving) "Saving" else "Done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        })
        message?.let { SectionHeader(it, warn = true) }

        SectionHeader("Details")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Item") },
            singleLine = true,
            isError = name.isBlank(),
            supportingText = if (name.isBlank()) ({ Text("Give the item a name.") }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 20.dp),
        )
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("Quantity") },
            placeholder = { Text("e.g. 2 or 1 dozen") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            placeholder = { Text("Brand, size, where to find it") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 14.dp),
        )
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.done,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = DivvyColors.Accent),
            )
            Text("Bought", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
        }

        SectionHeader("Added")
        InfoRow("By", item.addedBy ?: "Unknown")
        item.addedAt?.let { InfoRow("On", formatDate(it)) }

        RowDivider()
        TextButton(
            onClick = { confirmingRemove = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("Remove from list", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Warn)
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove ${item.name} from the list?") },
            text = { Text("It is removed for everyone in the household.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    onRemove()
                }) { Text("Remove", color = DivvyColors.Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = DivvyColors.Dim)
        Text(value, fontSize = 14.sp, color = DivvyColors.Ink)
    }
    RowDivider()
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ShoppingItemPreview() = DivvyTheme {
    ShoppingItemScreen(SampleData.shopping.first(), saving = false, onSave = { _, _, _ -> }, onToggle = {}, onRemove = {})
}
