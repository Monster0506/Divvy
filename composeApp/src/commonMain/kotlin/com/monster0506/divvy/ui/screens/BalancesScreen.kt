package com.monster0506.divvy.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
