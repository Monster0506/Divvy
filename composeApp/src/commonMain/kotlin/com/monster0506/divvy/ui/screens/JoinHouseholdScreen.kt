package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.state.JoinHouseholdState

@Composable
fun JoinHouseholdScreen(state: JoinHouseholdState, onContinue: () -> Unit, onRetry: () -> Unit) {
    AppScaffold(selected = null) {
        ScreenHeader("Who are you?")
        Text("Choose your name from this household.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 14.sp)
        when {
            state.loading -> Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Loading household members…")
            }
            state.members.isNotEmpty() -> Column(Modifier.padding(horizontal = 20.dp)) {
                state.members.forEach { person ->
                    Row(
                        Modifier.fillMaxWidth().clickable { state.select(person.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(state.selectedId == person.id, onClick = { state.select(person.id) })
                        Text(person.name, fontSize = 16.sp)
                    }
                }
            }
        }
        state.message?.let { message ->
            Text(message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp)
            TextButton(onClick = onRetry, enabled = !state.loading, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Try again")
            }
        }
        PrimaryButton("Continue", enabled = state.canContinue, onClick = onContinue)
    }
}
