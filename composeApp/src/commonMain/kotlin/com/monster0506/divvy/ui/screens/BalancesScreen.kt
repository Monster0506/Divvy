package com.monster0506.divvy.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import com.monster0506.divvy.platform.venmoUsernameProblem
import com.monster0506.divvy.ui.model.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.balances.BalancesPhase
import com.monster0506.divvy.balances.BalancesState
import com.monster0506.divvy.balances.Payment
import com.monster0506.divvy.balances.SettleTransfer
import com.monster0506.divvy.balances.isPaidBy
import com.monster0506.divvy.balances.isPaidTo
import com.monster0506.divvy.domain.centsToInput
import com.monster0506.divvy.domain.parsePriceCents
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.toMoney
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

/** The household's balances as the server reports them. Pull down, or tap Try again, to load them afresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveBalancesScreen(
    state: BalancesState,
    hasHousehold: Boolean,
    onRefresh: () -> Unit,
    onTab: (Tab) -> Unit = {},
    onSettle: (SettleTransfer, amountCents: Int) -> Unit = { _, _ -> },
    onUndo: () -> Unit = {},
    onPayWithVenmo: (SettleTransfer) -> Unit = {},
    onRequestWithVenmo: (SettleTransfer) -> Unit = {},
    onSaveVenmo: (person: Person, username: String, onSaved: () -> Unit) -> Unit = { _, _, _ -> },
    onEditVenmo: () -> Unit = {},
    /** This device's person; Venmo is offered only on payments they make or receive. */
    meId: String? = null,
) {
    var paying by remember { mutableStateOf<SettleTransfer?>(null) }
    var askingVenmo by remember { mutableStateOf<Person?>(null) }
    askingVenmo?.let { person ->
        VenmoUsernameDialog(
            person = person,
            saving = state.savingVenmo,
            serverMessage = state.venmoMessage,
            onSave = { onSaveVenmo(person, it) { askingVenmo = null } },
            onEdit = onEditVenmo,
            onDismiss = {
                askingVenmo = null
                onEditVenmo()
            },
        )
    }
    paying?.let { transfer ->
        PaymentDialog(
            transfer = transfer,
            onConfirm = { cents ->
                paying = null
                onSettle(transfer, cents)
            },
            onDismiss = { paying = null },
        )
    }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
        AppScaffold(selected = Tab.Balances, onSelect = onTab) {
            ScreenHeader("Balances")
            state.payment?.let { PaymentBanner(it, enabled = !state.recording, onUndo = onUndo) }
            when {
                !hasHousehold -> BalancesNote("Set up a household to see who owes what.")
                state.phase == BalancesPhase.Idle || state.phase == BalancesPhase.Loading ->
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                state.phase == BalancesPhase.Failed -> {
                    BalancesNote(state.message ?: "Could not load balances.")
                    TextButton(onClick = onRefresh, Modifier.padding(horizontal = 12.dp)) { Text("Try again") }
                }
                else -> {
                    if (state.message != null) SectionHeader(state.message, warn = true)
                    if (state.settled) SectionHeader("All settled up")
                    state.balances.forEach { BalanceRow(it) }
                    if (state.balances.isEmpty()) BalancesNote("Nobody in this household yet.")
                    if (state.transfers.isNotEmpty()) {
                        SectionHeader("Settle up", if (state.transfers.size == 1) "1 payment" else "${state.transfers.size} payments")
                        state.transfers.forEach {
                            SettleRow(
                                it,
                                meId = meId,
                                enabled = !state.recording,
                                onPay = { paying = it },
                                onVenmo = { if (it.toVenmo != null) onPayWithVenmo(it) else askingVenmo = it.to },
                                onRequest = { if (it.fromVenmo != null) onRequestWithVenmo(it) else askingVenmo = it.from },
                            )
                        }
                    }
                    if (state.otherCurrencies) {
                        Spacer(Modifier.height(8.dp))
                        BalancesNote("Other currencies are not shown yet.")
                    }
                }
            }
        }
    }
}

/**
 * "A pays B $X" with a Mark paid button. When this device's person is A, a Venmo button opens a payment to B; when
 * they are B, a Request button opens a Venmo request from A. Either asks for the other person's Venmo username first
 * if nobody has added it yet. Payments involving this device's person stand out.
 */
@Composable
private fun SettleRow(
    transfer: SettleTransfer,
    meId: String?,
    enabled: Boolean,
    onPay: () -> Unit,
    onVenmo: () -> Unit,
    onRequest: () -> Unit,
) {
    val iPay = transfer.isPaidBy(meId)
    val iAmPaid = transfer.isPaidTo(meId)
    val involvesMe = transfer.involvesMe || iPay || iAmPaid
    val weight = if (involvesMe) FontWeight.SemiBold else FontWeight.Normal
    val tint = if (involvesMe) DivvyColors.Accent else DivvyColors.Ink
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${transfer.from.name} pays ${transfer.to.name}", Modifier.weight(1f), fontSize = 14.sp, fontWeight = weight, color = tint)
        Text(transfer.amountCents.toMoney(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint)
        when {
            iPay -> SettleButton("Venmo", enabled = true, tint = tint, onClick = onVenmo, modifier = Modifier.padding(start = 10.dp))
            iAmPaid -> SettleButton("Request", enabled = true, tint = tint, onClick = onRequest, modifier = Modifier.padding(start = 10.dp))
        }
        SettleButton(
            "Mark paid",
            enabled = enabled,
            tint = tint,
            onClick = onPay,
            modifier = Modifier.padding(start = if (iPay || iAmPaid) 6.dp else 10.dp),
        )
    }
    RowDivider()
}

/** Asks for [person]'s Venmo username and saves it for the household, the same as Household settings. */
@Composable
private fun VenmoUsernameDialog(
    person: Person,
    saving: Boolean,
    serverMessage: String?,
    onSave: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(person.id) { mutableStateOf("") }
    val problem = venmoUsernameProblem(text)
    val message = serverMessage ?: problem
    val canSave = !saving && problem == null && text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${person.name}'s Venmo") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onEdit()
                },
                label = { Text("Venmo username") },
                prefix = { Text("@") },
                singleLine = true,
                enabled = !saving,
                isError = message != null,
                supportingText = { Text(message ?: "Saved for the household, so housemates can pay or request from ${person.name} with Venmo.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettleButton(label: String, enabled: Boolean, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (enabled) tint else DivvyColors.Line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/** Confirms a payment. The amount can be lowered to record a part payment. */
@Composable
private fun PaymentDialog(transfer: SettleTransfer, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember(transfer) { mutableStateOf(centsToInput(transfer.amountCents)) }
    val cents = parsePriceCents(text)
    val valid = cents != null && cents in 1..transfer.amountCents
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${transfer.from.name} paid ${transfer.to.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        Text(if (valid) "Part of ${transfer.amountCents.toMoney()} is fine." else "Enter up to ${transfer.amountCents.toMoney()}.")
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(cents!!) }, enabled = valid) { Text("Record payment") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown for a few seconds after a payment is recorded, with a way to take it back. */
@Composable
private fun PaymentBanner(payment: Payment, enabled: Boolean, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DivvyColors.Page).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Recorded: ${payment.from.name} paid ${payment.to.name} ${payment.amountCents.toMoney()}",
            Modifier.weight(1f),
            fontSize = 13.sp,
            color = DivvyColors.Ink,
        )
        TextButton(onClick = onUndo, enabled = enabled) { Text("Undo") }
    }
}

@Composable
private fun BalancesNote(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), fontSize = 14.sp, color = DivvyColors.Dim)
}

@Composable
private fun BalanceRow(balance: Balance) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(balance.person, size = 36)
        val net by animateIntAsState(balance.netCents, tween(450))
        val tint by animateColorAsState(if (balance.netCents > 0) DivvyColors.Accent else DivvyColors.Ink, tween(300))
        Column(Modifier.weight(1f)) {
            Text(balance.person.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
            val caption = when {
                balance.netCents > 0 -> "is owed"
                balance.netCents < 0 -> "owes"
                else -> "settled up"
            }
            Text(caption, fontSize = 12.sp, color = DivvyColors.Dim)
        }
        Text(kotlin.math.abs(net).toMoney(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
    RowDivider()
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun LiveBalancesPreview() = DivvyTheme {
    LiveBalancesScreen(BalancesState(BalancesPhase.Ready, SampleData.balances), hasHousehold = true, onRefresh = {})
}
