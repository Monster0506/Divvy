package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
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
    onTab: (Tab) -> Unit = {},
) {
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
    }
}

@Preview(widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun AccountPreview() = DivvyTheme {
    AccountScreen(SampleData.andrew, "Apartment 4B", SampleData.people, Settings(), {}, {}, {}, {})
}
