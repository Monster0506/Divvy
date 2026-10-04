"""Arithmetic checks on an extracted receipt. They only report and never change the values."""

from divvy_ml.receipts.schemas import ExtractedReceipt

TOLERANCE_CENTS = 1


def check_receipt(receipt: ExtractedReceipt) -> list[str]:
    warnings: list[str] = []

    for number, item in enumerate(receipt.items, start=1):
        if item.unit_price_cents is None:
            continue
        expected = round(item.quantity * item.unit_price_cents)
        if abs(expected - item.total_cents) > TOLERANCE_CENTS:
            warnings.append(
                f"Item {number} ({item.name!r}): quantity x unit price is {_money(expected)} "
                f"but the line total is {_money(item.total_cents)}"
            )

    items = sum(item.total_cents for item in receipt.items)
    discounts = sum(discount.amount_cents for discount in receipt.discounts)
    after_discounts = items - discounts

    if receipt.subtotal_cents is not None and abs(after_discounts - receipt.subtotal_cents) > 1:
        warnings.append(
            f"Items less discounts come to {_money(after_discounts)} "
            f"but the subtotal is {_money(receipt.subtotal_cents)}"
        )

    if receipt.subtotal_cents is not None and receipt.tax_cents is not None:
        expected_total = receipt.subtotal_cents + receipt.tax_cents
        if receipt.total_cents is not None and abs(expected_total - receipt.total_cents) > 1:
            warnings.append(
                f"Subtotal plus tax is {_money(expected_total)} "
                f"but the total is {_money(receipt.total_cents)}"
            )
    elif receipt.subtotal_cents is None and receipt.total_cents is not None:
        expected_total = after_discounts + (receipt.tax_cents or 0)
        if abs(expected_total - receipt.total_cents) > 1:
            warnings.append(
                f"Items less discounts plus tax are {_money(expected_total)} "
                f"but the total is {_money(receipt.total_cents)}"
            )

    return warnings


def _money(cents: int) -> str:
    sign = "-" if cents < 0 else ""
    return f"{sign}{abs(cents) // 100}.{abs(cents) % 100:02d}"
