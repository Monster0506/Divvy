from divvy_ml.receipts import (
    Discount,
    ExtractedItem,
    ExtractedReceipt,
    reconcile,
    with_reconciliation,
)
from divvy_ml.receipts.router import SAMPLE_RECEIPT


def receipt(total, tax=72, discount=100):
    return ExtractedReceipt(
        items=[
            ExtractedItem(name="MILK", total_cents=429),
            ExtractedItem(name="BREAD", total_cents=698),
        ],
        discounts=[Discount(description="SAVINGS", amount_cents=discount)] if discount else [],
        tax_cents=tax,
        total_cents=total,
    )


def test_balanced_receipt_has_no_difference_or_warning():
    result = reconcile(receipt(1099))
    assert result.balanced
    assert result.difference_cents == 0
    assert with_reconciliation(receipt(1099)).warnings == []


def test_under_reports_exact_difference():
    result = reconcile(receipt(1109))
    assert result.difference_cents == 10
    warning = with_reconciliation(receipt(1109)).warnings[0]
    assert "under by 10 cents" in warning


def test_over_reports_exact_difference():
    result = reconcile(receipt(1090))
    assert result.difference_cents == -9
    assert "over by 9 cents" in with_reconciliation(receipt(1090)).warnings[0]


def test_missing_tax_and_discounts_count_as_zero():
    r = receipt(1127, tax=None, discount=0)
    assert reconcile(r).balanced


def test_refund_lines_reduce_the_total():
    r = ExtractedReceipt(
        items=[
            ExtractedItem(name="MILK", total_cents=500),
            ExtractedItem(name="RETURN", total_cents=-200),
        ],
        total_cents=300,
    )
    assert reconcile(r).balanced


def test_no_printed_total_is_not_checked():
    r = receipt(None)
    assert reconcile(r) is None
    assert with_reconciliation(r) is r


def test_existing_warnings_are_kept_and_input_is_unchanged():
    r = receipt(1109).model_copy(update={"warnings": ["blurry"]})
    out = with_reconciliation(r)
    assert out.warnings[0] == "blurry"
    assert len(out.warnings) == 2
    assert r.warnings == ["blurry"]


def test_sample_receipt_reconciles():
    assert reconcile(SAMPLE_RECEIPT).balanced
