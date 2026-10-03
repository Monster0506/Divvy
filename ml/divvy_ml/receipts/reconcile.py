"""Check that extracted items add up to the printed total."""

from dataclasses import dataclass

from divvy_ml.receipts.schemas import ExtractedReceipt


@dataclass(frozen=True)
class Reconciliation:
    computed_cents: int
    printed_cents: int
    # printed minus computed: positive means items are under the printed total
    difference_cents: int

    @property
    def balanced(self) -> bool:
        return self.difference_cents == 0


def reconcile(receipt: ExtractedReceipt) -> Reconciliation | None:
    """Compare items + tax - discounts with the printed total; None if no total was read."""
    if receipt.total_cents is None:
        return None
    computed = (
        sum(item.total_cents for item in receipt.items)
        + (receipt.tax_cents or 0)
        - sum(d.amount_cents for d in receipt.discounts)
    )
    return Reconciliation(computed, receipt.total_cents, receipt.total_cents - computed)


def reconciliation_warning(result: Reconciliation) -> str | None:
    if result.balanced:
        return None
    side = "under" if result.difference_cents > 0 else "over"
    return (
        f"items do not match printed total: {side} by {abs(result.difference_cents)} cents "
        f"(items+tax-discounts {result.computed_cents}, printed {result.printed_cents})"
    )


def with_reconciliation(receipt: ExtractedReceipt) -> ExtractedReceipt:
    """Return a copy of the receipt with a warning added if it does not reconcile."""
    result = reconcile(receipt)
    warning = reconciliation_warning(result) if result else None
    if warning is None:
        return receipt
    return receipt.model_copy(update={"warnings": [*receipt.warnings, warning]})
