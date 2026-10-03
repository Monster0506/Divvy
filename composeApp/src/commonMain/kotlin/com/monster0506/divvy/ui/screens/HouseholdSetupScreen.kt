package com.monster0506.divvy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.PrimaryButton
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

@Composable
fun HouseholdSetupScreen(
    householdName: String,
    members: List<Person>,
    newMember: String,
    onNameChange: (String) -> Unit,
    onNewMemberChange: (String) -> Unit,
    onAddMember: () -> Unit,
    onRemoveMember: (id: String) -> Unit,
    canRemove: (id: String) -> Boolean = { true },
    onStart: () -> Unit,
) {
    AppScaffold(selected = null) {
        ScreenHeader("Create household")
        OutlinedTextField(
            value = householdName,
            onValueChange = onNameChange,
            label = { Text("Household name") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp),
        )
        SectionHeader("Members", members.size.toString())
        members.forEach { person ->
            key(person.id) {
                val enter = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(enter) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Avatar(person)
                            Text(person.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                            if (canRemove(person.id)) {
                                TextButton(onClick = { onRemoveMember(person.id) }) {
                                    Text("Remove", fontSize = 13.sp, color = DivvyColors.Dim)
                                }
                            }
                        }
                        RowDivider()
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newMember,
                onValueChange = onNewMemberChange,
                placeholder = { Text("Add a name") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onAddMember, shape = RoundedCornerShape(8.dp)) {
                Text("Add", fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            PrimaryButton("Start", enabled = members.size >= 2, onClick = onStart)
        }
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun HouseholdSetupPreview() = DivvyTheme {
    HouseholdSetupScreen(
        householdName = "Apartment 4B",
        members = SampleData.people,
        newMember = "",
        onNameChange = {}, onNewMemberChange = {}, onAddMember = {}, onRemoveMember = {}, onStart = {},
    )
}
