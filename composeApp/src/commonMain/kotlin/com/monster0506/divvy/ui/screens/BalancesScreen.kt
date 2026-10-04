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
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
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
import com.monster0506.divvy.domain.centsToInput
import com.monster0506.divvy.domain.parsePriceCents
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.Avatar
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.Transfer
import com.monster0506.divvy.ui.model.toMoney
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

@Composable
fun BalancesScreen(
    balances: List<Balance>,
    transfers: List<Transfer>,
    onTogglePaid: (index: Int) -> Unit,
    onTab: (Tab) -> Unit = {},
) {
    AppScaffold(selected = Tab.Balances, onSelect = onTab) {
        ScreenHeader("Balances")
        balances.forEach { BalanceRow(it) }
        SectionHeader("Settle up", "${transfers.size} payments")
        transfers.forEachIndexed { i, t -> TransferRow(t) { onTogglePaid(i) } }
    }
}

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
    onAddVenmo: () -> Unit = {},
) {
    var paying by remember { mutableStateOf<SettleTransfer?>(null) }
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
                                enabled = !state.recording,
                                onPay = { paying = it },
                                onVenmo = { onPayWithVenmo(it) },
                                onAddVenmo = onAddVenmo,
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
 * "A pays B $X" with buttons to pay with Venmo, when B has a Venmo username, and to record the payment. Without a
 * username the row says where to add one. Payments involving this device's person stand out.
 */
@Composable
private fun SettleRow(
    transfer: SettleTransfer,
    enabled: Boolean,
    onPay: () -> Unit,
    onVenmo: () -> Unit,
    onAddVenmo: () -> Unit,
) {
    val weight = if (transfer.involvesMe) FontWeight.SemiBold else FontWeight.Normal
    val tint = if (transfer.involvesMe) DivvyColors.Accent else DivvyColors.Ink
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${transfer.from.name} pays ${transfer.to.name}", Modifier.weight(1f), fontSize = 14.sp, fontWeight = weight, color = tint)
            Text(transfer.amountCents.toMoney(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (transfer.toVenmo == null) {
                Text(
                    "No Venmo for ${transfer.to.name}. Add it in Household settings.",
                    Modifier.weight(1f).clickable(onClick = onAddVenmo),
                    fontSize = 12.sp,
                    color = DivvyColors.Accent,
                )
            } else {
                Spacer(Modifier.weight(1f))
                SettleButton("Pay with Venmo", enabled = true, tint = tint, onClick = onVenmo)
            }
            SettleButton("Mark paid", enabled = enabled, tint = tint, onClick = onPay, modifier = Modifier.padding(start = 10.dp))
        }
    }
    RowDivider()
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

@Composable
private fun TransferRow(transfer: Transfer, onTogglePaid: () -> Unit) {
    val color by animateColorAsState(if (transfer.paid) DivvyColors.Dim else DivvyColors.Ink, tween(250))
    val edge by animateColorAsState(if (transfer.paid) DivvyColors.Line else DivvyColors.Ink, tween(250))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${transfer.from.name} to ${transfer.to.name}", Modifier.weight(1f), fontSize = 14.sp, color = color)
        Text(transfer.amountCents.toMoney(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
        OutlinedButton(
            onClick = onTogglePaid,
            modifier = Modifier.padding(start = 10.dp),
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, edge),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Text(if (transfer.paid) "Undo" else "Mark paid", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
    RowDivider()
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun BalancesPreview() = DivvyTheme {
    BalancesScreen(SampleData.balances, SampleData.transfers, {})
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun LiveBalancesPreview() = DivvyTheme {
    LiveBalancesScreen(BalancesState(BalancesPhase.Ready, SampleData.balances), hasHousehold = true, onRefresh = {})
}
