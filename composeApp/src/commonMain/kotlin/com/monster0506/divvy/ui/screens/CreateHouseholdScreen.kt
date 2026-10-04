package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.state.CreateHouseholdState

@Composable
fun CreateHouseholdScreen(state: CreateHouseholdState, onContinue: () -> Unit) {
    AppScaffold(selected = null) {
        ScreenHeader("Name your household")
        OutlinedTextField(
            value = state.name,
            onValueChange = state::onNameChange,
            label = { Text("Household name") },
            singleLine = true,
            enabled = !state.submitting,
            isError = state.fieldMessage != null,
            supportingText = state.fieldMessage?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onContinue() }),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (state.submitting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Creating your household…", fontSize = 13.sp)
                }
            }
            PrimaryButton(
                if (state.error != null) "Try again" else "Continue",
                enabled = state.canSubmit,
                onClick = onContinue,
            )
        }
    }
}