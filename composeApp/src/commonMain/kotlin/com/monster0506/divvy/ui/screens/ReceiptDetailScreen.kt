package com.monster0506.divvy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monster0506.divvy.ui.components.AppScaffold
import com.monster0506.divvy.ui.components.RowDivider
import com.monster0506.divvy.ui.components.ScreenHeader
import com.monster0506.divvy.ui.components.SectionHeader
import com.monster0506.divvy.ui.components.Tab
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ownersLabel
import com.monster0506.divvy.ui.model.toMoney
import com.monster0506.divvy.ui.preview.SampleData
import com.monster0506.divvy.ui.theme.DivvyColors
import com.monster0506.divvy.ui.theme.DivvyTheme

@Composable
fun ReceiptDetailScreen(
    receipt: ReceiptDraft,
    shares: List<Pair<Person, Int>>,
    onViewPhoto: () -> Unit,
    onReopen: () -> Unit,
    onTab: (Tab) -> Unit = {},
    notice: String? = null,
) {
    AppScaffold(selected = Tab.Receipts, onSelect = onTab) {
        ScreenHeader(
            title = receipt.store,
            metaStart = receipt.date,
            metaEnd = buildAnnotatedString {
                append("Paid by ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = DivvyColors.Ink)) { append(receipt.payer.name) }
            },
        )
        if (notice != null) SectionHeader(notice, warn = true)
        SectionHeader("Items", receipt.totalCents.toMoney())
        receipt.lines.forEach { line ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(line.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                    Text(line.priceCents.toMoney(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DivvyColors.Ink)
                }
                Text(line.ownersLabel(receipt.people), fontSize = 12.sp, color = DivvyColors.Dim)
            }
            RowDivider()
        }
        SectionHeader("Each person's share")
        shares.forEach { (person, cents) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(person.name, fontSize = 15.sp, color = DivvyColors.Ink)
                Text(cents.toMoney(), fontSize = 15.sp, color = DivvyColors.Ink)
            }
        }
        Spacer(Modifier.height(10.dp))
        DetailButton("View receipt photo", DivvyColors.Ink, onViewPhoto)
        DetailButton("Reopen to correct", DivvyColors.Dim, onReopen)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DetailButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp).height(46.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (color == DivvyColors.Ink) DivvyColors.Ink else DivvyColors.Outline),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ReceiptDetailPreview() = DivvyTheme {
    ReceiptDetailScreen(SampleData.resolvedDraft, SampleData.shares, {}, {})
}
