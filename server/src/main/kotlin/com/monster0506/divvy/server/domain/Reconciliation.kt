package com.monster0506.divvy.server.domain

/** How a receipt's line items compare with its stated subtotal, tax and total, in minor units. */
data class Reconciliation(
    val itemsTotalMinor: Long,
    /** Total minus (items plus tax). Zero when the receipt adds up. */
    val totalDifferenceMinor: Long,
    /** Subtotal minus items; null when the receipt has no subtotal. */
    val subtotalDifferenceMinor: Long?,
) {
    val reconciled: Boolean get() = totalDifferenceMinor == 0L && (subtotalDifferenceMinor ?: 0L) == 0L
}

fun Receipt.reconcile(): Reconciliation {
    val itemsTotal = items.sumOf { it.total.amountMinor }
    return Reconciliation(
        itemsTotalMinor = itemsTotal,
        totalDifferenceMinor = total.amountMinor - (itemsTotal + (tax?.amountMinor ?: 0L)),
        subtotalDifferenceMinor = subtotal?.let { it.amountMinor - itemsTotal },
    )
}
