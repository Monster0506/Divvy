package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.state.HouseholdSettingsState
import com.monster0506.divvy.ui.state.MIN_MEMBERS
import com.monster0506.divvy.ui.theme.DivvyColors
import kotlinx.coroutines.launch

private fun HouseholdMember.asPerson() = Person(id, name, initialsFor(name))

@Composable
fun HouseholdSettingsScreen(state: HouseholdSettingsState, onBack: () -> Unit, onTab: (Tab) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.refresh() }

    AppScaffold(selected = Tab.Account, onSelect = onTab) {
        ScreenHeader("Household settings")
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Back to account") }

        state.error?.let {
            Text(
                it,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
            )
        }
        if (!state.loaded) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { scope.launch { state.refresh() } }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Try again")
                }
            }
            return@AppScaffold
        }

        SectionHeader("Name")
        OutlinedTextField(
            value = state.nameDraft,
            onValueChange = state::onNameDraftChange,
            label = { Text("Household name") },
            singleLine = true,
            enabled = !state.busy,
            isError = state.nameFieldMessage != null,
            supportingText = state.nameFieldMessage?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { scope.launch { state.saveName() } }),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp)) {
            PrimaryButton("Save name", enabled = state.canSaveName, onClick = { scope.launch { state.saveName() } })
        }

        SectionHeader("Members", "${state.members.size} active")
        state.members.forEach { member ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(member.asPerson(), size = 32)
                Column(Modifier.weight(1f)) {
                    Text(member.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                    Text(
                        member.venmoUsername?.let { "Venmo @$it" } ?: "No Venmo username",
                        fontSize = 12.sp,
                        color = DivvyColors.Dim,
                    )
                }
                TextButton(onClick = { state.editVenmo(member) }, enabled = !state.busy) {
                    Text("Venmo")
                }
                TextButton(onClick = { state.requestArchive(member) }, enabled = state.canArchive(member)) {
                    Text("Archive")
                }
            }
            RowDivider()
        }
        if (state.members.size <= MIN_MEMBERS) {
            Text(
                "A household needs at least $MIN_MEMBERS active members.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 13.sp,
                color = DivvyColors.Dim,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.memberDraft,
                onValueChange = state::onMemberDraftChange,
                label = { Text("Add a member") },
                singleLine = true,
                enabled = !state.busy,
                isError = state.memberError != null,
                supportingText = state.memberError?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { scope.launch { state.addMember() } }),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { scope.launch { state.addMember() } },
                enabled = !state.busy,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Add") }
        }

        if (state.archivedMembers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = state::toggleArchived)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Archived (${state.archivedMembers.size})",
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DivvyColors.Ink,
                )
                Text(
                    if (state.showArchived) "Hide" else "Show",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DivvyColors.Accent,
                )
            }
            if (state.showArchived) {
                state.archivedMembers.forEach { member ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(member.asPerson(), size = 32)
                        Text(member.name, fontSize = 15.sp, color = DivvyColors.Dim)
                    }
                    RowDivider()
                }
            }
        }
    }

    state.editingVenmo?.let { member ->
        AlertDialog(
            onDismissRequest = state::cancelVenmo,
            title = { Text("${member.name}'s Venmo") },
            text = {
                OutlinedTextField(
                    value = state.venmoDraft,
                    onValueChange = state::onVenmoDraftChange,
                    label = { Text("Venmo username") },
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = state.venmoMessage != null,
                    supportingText = {
                        Text(state.venmoMessage ?: "Housemates use this to pay ${member.name} when settling up. Leave it empty to remove it.")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { scope.launch { state.saveVenmo() } }),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { state.saveVenmo() } },
                    enabled = !state.busy && state.venmoMessage == null,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = state::cancelVenmo) { Text("Cancel") }
            },
        )
    }

    state.archiving?.let { member ->
        AlertDialog(
            onDismissRequest = state::cancelArchive,
            title = { Text("Archive ${member.name}?") },
            text = {
                Text("${member.name} stays in past receipts and balances but won't be added to new splits.")
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { state.confirmArchive() } }) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = state::cancelArchive) { Text("Cancel") }
            },
        )
    }
}
