package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.state.AddMembersState
import com.monster0506.divvy.ui.state.MIN_MEMBERS

@Composable
fun AddMembersScreen(state: AddMembersState, onFinish: () -> Unit) {
    AppScaffold(selected = null) {
        ScreenHeader("Who lives here?")
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = state::onDraftChange,
                label = { Text("Name") },
                singleLine = true,
                enabled = !state.submitting,
                isError = state.draftError != null,
                supportingText = state.draftError?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { state.add() }),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { state.add() }, enabled = !state.submitting, modifier = Modifier.padding(top = 8.dp)) {
                Text("Add")
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.rows.forEach { row ->
                key(row.name) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(row.name, fontSize = 16.sp)
                            if (state.canRemove(row)) {
                                TextButton(onClick = { state.remove(row) }) { Text("Remove") }
                            }
                        }
                        row.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.rows.size < MIN_MEMBERS) {
                Text("Add at least $MIN_MEMBERS people to continue.", fontSize = 13.sp)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (state.submitting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Adding your people...", fontSize = 13.sp)
                }
            }
            PrimaryButton(
                if (state.error != null || state.rows.any { it.error != null }) "Try again" else "Finish",
                enabled = state.canFinish,
                onClick = onFinish,
            )
        }
    }
}