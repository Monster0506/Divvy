package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.data.api.ApiConfig
import com.monster0506.divvy.data.api.normalizedOrNull
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.DivvyIcons
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.Settings
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

@Composable
fun AccountScreen(
    me: Person,
    householdName: String,
    people: List<Person>,
    settings: Settings,
    onHouseholdNameChange: (String) -> Unit,
    onEditPerson: (id: String) -> Unit,
    onAddMember: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    settingsMessage: String? = null,
    apiConfig: ApiConfig = ApiConfig(),
    onApiConfigChange: (ApiConfig) -> Unit = {},
    onTab: (Tab) -> Unit = {},
) {
    var serverUrl by remember(apiConfig) { mutableStateOf(apiConfig.baseUrl) }
    var serverKey by remember(apiConfig) { mutableStateOf(apiConfig.apiKey) }
    var serverError by remember(apiConfig) { mutableStateOf(false) }
    AppScaffold(selected = Tab.Account, onSelect = onTab) {
        ScreenHeader("Account")
        Row(
            Modifier.fillMaxWidth().clickable { onEditPerson(me.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(me, size = 56)
            Column(Modifier.weight(1f)) {
                Text(me.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
                Text("Member of $householdName", fontSize = 13.sp, color = DivvyColors.Dim)
            }
            Text("Edit", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DivvyColors.Accent)
        }

        SectionHeader("Household", "${people.size} members")
        OutlinedTextField(
            value = householdName,
            onValueChange = onHouseholdNameChange,
            label = { Text("Household name") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        )
        RowDivider()
        people.forEach { person ->
            Row(
                Modifier.fillMaxWidth().clickable { onEditPerson(person.id) }.padding(horizontal = 20.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(person, size = 32)
                Text(
                    if (person.id == me.id) "${person.name} (you)" else person.name,
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DivvyColors.Ink,
                )
                Icon(DivvyIcons.ChevronRight, contentDescription = null, tint = DivvyColors.Dim)
            }
            RowDivider()
        }
        Text(
            "+ Add member",
            Modifier.fillMaxWidth().clickable(onClick = onAddMember).padding(horizontal = 20.dp, vertical = 14.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = DivvyColors.Accent,
        )

        SectionHeader("Receipts")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-assign items", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                Text(
                    "Items Divvy is sure about are assigned for you. The rest wait for you to confirm.",
                    fontSize = 12.sp,
                    color = DivvyColors.Dim,
                )
            }
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
        if (settingsMessage != null) {
            Text(
                settingsMessage,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = DivvyColors.Warn,
            )
        }

        SectionHeader("Payments")
        OutlinedTextField(
            value = settings.venmoUsername,
            onValueChange = { onSettingsChange(settings.copy(venmoUsername = it.removePrefix("@").trim())) },
            label = { Text("Venmo username") },
            prefix = { Text("@") },
            supportingText = { Text("Housemates use this to pay you when settling up") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        )

        SectionHeader("Server")
        Text(
            "On a phone, use a reachable HTTPS address or your computer's LAN address, not localhost. The server must include the receipt upload route.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fontSize = 13.sp,
            color = DivvyColors.Dim,
        )
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
}

@Preview(widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun AccountPreview() = DivvyTheme {
    AccountScreen(SampleData.andrew, "Apartment 4B", SampleData.people, Settings(), {}, {}, {}, {})
}
