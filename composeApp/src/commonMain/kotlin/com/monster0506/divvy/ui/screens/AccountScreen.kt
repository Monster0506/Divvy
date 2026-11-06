package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.monster0506.divvy.connection.ConnectionStatus
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.data.api.ApiConfig
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.api.SettingsResult
import com.monster0506.divvy.data.api.normalizedOrNull
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.DivvyIcons
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.ServerProblemMessage
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.Settings
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.state.HouseholdMembersState
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme
import kotlinx.coroutines.launch

/**
 * This device's person, the household (its name, members, their Venmo usernames, adding someone), auto-assign
 * settings and the server. Anything not loaded from the server yet says so instead of showing a placeholder. With
 * [serverStatus] set, the server can't be used: only the server settings show, under one message, and there are
 * no tabs to leave by.
 */
@Composable
fun AccountScreen(
    me: Person?,
    householdName: String,
    nameLoaded: Boolean,
    people: List<Person>,
    household: HouseholdMembersState,
    settings: Settings?,
    onHouseholdNameChange: (String) -> Unit,
    onEditPerson: (id: String) -> Unit,
    onSettingsChange: (Settings) -> Unit,
    /** Called after a member is added or their Venmo username changes, so the rest of the app can reload. */
    onMembersChanged: () -> Unit = {},
    settingsMessage: String? = null,
    apiConfig: ApiConfig = ApiConfig(),
    onApiConfigChange: (ApiConfig) -> Unit = {},
    onTab: (Tab) -> Unit = {},
    householdNameMessage: String? = null,
    serverStatus: ConnectionStatus? = null,
    onRetryConnection: () -> Unit = {},
) {
    if (serverStatus != null) {
        AppScaffold(selected = null) {
            ScreenHeader("Server")
            ServerProblemMessage(serverStatus, onRetryConnection)
            ServerSettings(apiConfig, onApiConfigChange)
        }
        return
    }
    val scope = rememberCoroutineScope()
    AppScaffold(selected = Tab.Account, onSelect = onTab) {
        ScreenHeader("Account")
        if (me != null) {
            Row(
                Modifier.fillMaxWidth().clickable { onEditPerson(me.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(me, size = 56)
                Column(Modifier.weight(1f)) {
                    Text(me.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
                    if (nameLoaded) Text("Member of $householdName", fontSize = 13.sp, color = DivvyColors.Dim)
                }
                Text("Edit", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
            }
        }

        SectionHeader("Household", if (household.loaded) "${household.members.size} members" else null)
        household.error?.let { SectionHeader(it, warn = true) }
        OutlinedTextField(
            value = householdName,
            onValueChange = onHouseholdNameChange,
            label = { Text("Household name") },
            placeholder = if (nameLoaded) null else ({ Text("Loading…") }),
            enabled = nameLoaded,
            singleLine = true,
            isError = householdNameMessage != null,
            supportingText = householdNameMessage?.let { message -> { Text(message) } },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        )
        RowDivider()
        if (!household.loaded) Loading("Loading members…")
        household.members.forEach { member ->
            val person = people.find { it.id == member.id }
            MemberRow(
                member = member,
                person = person ?: Person(member.id, member.name, initialsFor(member.name)),
                isMe = member.id == me?.id,
                venmoEnabled = !household.busy,
                onEdit = if (person != null) ({ onEditPerson(member.id) }) else null,
                onVenmo = { household.editVenmo(member) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val add = { scope.launch { if (household.addMember()) onMembersChanged() } }
            OutlinedTextField(
                value = household.memberDraft,
                onValueChange = household::onMemberDraftChange,
                label = { Text("Add a member") },
                singleLine = true,
                enabled = household.loaded && !household.busy,
                isError = household.memberError != null,
                supportingText = household.memberError?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { add() },
                enabled = household.loaded && !household.busy,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Add") }
        }
        if (household.archivedMembers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = household::toggleArchived)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Archived (${household.archivedMembers.size})",
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DivvyColors.Ink,
                )
                Text(
                    if (household.showArchived) "Hide" else "Show",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DivvyColors.Accent,
                )
            }
            if (household.showArchived) {
                household.archivedMembers.forEach { member ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(Person(member.id, member.name, initialsFor(member.name)), size = 32)
                        Text(member.name, fontSize = 15.sp, color = DivvyColors.Dim)
                    }
                    RowDivider()
                }
            }
        }

        SectionHeader("Receipts")
        if (settings == null) {
            Loading("Loading auto-assign settings…")
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Auto-assign items",
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DivvyColors.Ink,
                )
                Switch(
                    checked = settings.autoAssign,
                    onCheckedChange = { onSettingsChange(settings.copy(autoAssign = it)) },
                )
            }
            if (settings.autoAssign) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        "Confidence needed: ${(settings.autoAssignThreshold * 100).roundToInt()}%",
                        fontSize = 13.sp,
                        color = DivvyColors.Dim,
                    )
                    Slider(
                        value = settings.autoAssignThreshold,
                        onValueChange = { onSettingsChange(settings.copy(autoAssignThreshold = it)) },
                        valueRange = 0.5f..0.99f,
                    )
                }
            }
        }
        if (settingsMessage != null) {
            Text(
                settingsMessage,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = DivvyColors.Warn,
            )
        }

        SectionHeader("Server")
        ServerSettings(apiConfig, onApiConfigChange)
    }

    household.editingVenmo?.let { member ->
        AlertDialog(
            onDismissRequest = household::cancelVenmo,
            title = { Text("${member.name}'s Venmo") },
            text = {
                OutlinedTextField(
                    value = household.venmoDraft,
                    onValueChange = household::onVenmoDraftChange,
                    label = { Text("Venmo username") },
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = !household.busy,
                    isError = household.venmoMessage != null,
                    supportingText = {
                        Text(household.venmoMessage ?: "Housemates use this to pay ${member.name} when settling up. Leave it empty to remove it.")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { scope.launch { if (household.saveVenmo()) onMembersChanged() } }),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { if (household.saveVenmo()) onMembersChanged() } },
                    enabled = !household.busy && household.venmoMessage == null,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = household::cancelVenmo) { Text("Cancel") } },
        )
    }
}

/** A member: tap to edit them (once they have loaded), or set where they receive payments. */
@Composable
private fun MemberRow(
    member: HouseholdMember,
    person: Person,
    isMe: Boolean,
    venmoEnabled: Boolean,
    onEdit: (() -> Unit)?,
    onVenmo: () -> Unit,
) {
    val tap = if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier
    Row(
        Modifier.fillMaxWidth().then(tap).padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(person, size = 32)
        Column(Modifier.weight(1f)) {
            Text(
                if (isMe) "${person.name} (you)" else person.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = DivvyColors.Ink,
            )
            Text(
                member.venmoUsername?.let { "Venmo @$it" } ?: "No Venmo username",
                fontSize = 12.sp,
                color = DivvyColors.Dim,
            )
        }
        TextButton(onClick = onVenmo, enabled = venmoEnabled) { Text("Venmo") }
        if (onEdit != null) Icon(DivvyIcons.ChevronRight, contentDescription = null, tint = DivvyColors.Dim)
    }
    RowDivider()
}

@Composable
private fun Loading(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 13.sp, color = DivvyColors.Dim)
}

@Composable
private fun ColumnScope.ServerSettings(apiConfig: ApiConfig, onApiConfigChange: (ApiConfig) -> Unit) {
    var serverUrl by remember(apiConfig) { mutableStateOf(apiConfig.baseUrl) }
    var serverKey by remember(apiConfig) { mutableStateOf(apiConfig.apiKey) }
    var serverError by remember(apiConfig) { mutableStateOf(false) }
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it; serverError = false },
        label = { Text("Server URL") },
        placeholder = { Text("https://your-server.example") },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
    OutlinedTextField(
        value = serverKey,
        onValueChange = { serverKey = it; serverError = false },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
    if (serverError) Text(
        "Enter a full http:// or https:// URL and a nonempty API key.",
        modifier = Modifier.padding(horizontal = 20.dp),
        fontSize = 13.sp,
        color = DivvyColors.Dim,
    )
    Button(
        onClick = {
            val config = ApiConfig(serverUrl, serverKey).normalizedOrNull()
            if (config == null) serverError = true else onApiConfigChange(config)
        },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    ) { Text("Save server settings") }
}

private fun previewHousehold() = HouseholdMembersState(
    load = { SettingsResult.Ok(SampleData.people.map { HouseholdMember(it.id, it.name) }) },
    create = { CreatePersonResult.Created("new") },
)

@Preview(widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun AccountPreview() = DivvyTheme {
    AccountScreen(
        SampleData.andrew, "Apartment 4B", nameLoaded = true, SampleData.people, remember { previewHousehold() },
        Settings(), {}, {}, {},
    )
}

@Preview(widthDp = 390, heightDp = 600, showBackground = true)
@Composable
private fun AccountServerProblemPreview() = DivvyTheme {
    AccountScreen(
        null, "", nameLoaded = false, emptyList(), remember { previewHousehold() }, null, {}, {}, {},
        serverStatus = ConnectionStatus.KeyRejected,
    )
}
