from pathlib import Path

import pytest

from divvy_ml.eval.extraction import (
    count_correct_items,
    evaluate,
    format_report,
    load_receipt,
    reconciles,
)
from divvy_ml.receipts import ExtractedItem, ExtractedReceipt

DATA = Path(__file__).parent / "data"


def receipt(items, total=None, tax=0):
    return ExtractedReceipt(
        items=[ExtractedItem(name=n, total_cents=p) for n, p in items],
        tax_cents=tax,
        total_cents=total,
    )


def test_fixture_labels_all_reconcile():
    paths = sorted((DATA / "receipts").glob("*.json"))
    assert paths
    assert all(reconciles(load_receipt(p)) for p in paths)


def test_reconciles_requires_exact_cents():
    assert reconciles(receipt([("A", 100), ("B", 250)], total=385, tax=35))
    assert not reconciles(receipt([("A", 100)], total=101))
    assert not reconciles(receipt([("A", 100)], total=None))


def test_item_matching_is_one_to_one_and_ignores_case():
    truth = receipt([("Milk", 300), ("milk", 300)])
    assert count_correct_items(truth, receipt([("MILK", 300)])) == 1
    assert count_correct_items(truth, receipt([("MILK", 300), ("Milk.", 300)])) == 2
    assert count_correct_items(truth, receipt([("MILK", 299)])) == 0


def test_evaluate_fixture_set():
    report = evaluate(DATA / "receipts", DATA / "predictions")
    assert len(report.scores) == 4
    assert report.item_price_accuracy == pytest.approx(7 / 12)
    assert report.reconciliation_rate == pytest.approx(3 / 4)
    assert report.total_accuracy == pytest.approx(2 / 4)
    assert "item price accuracy: 58.3%" in format_report(report)


def test_missing_predictions_score_zero(tmp_path):
    report = evaluate(DATA / "receipts", tmp_path)
    assert report.item_price_accuracy == 0
    assert report.reconciliation_rate == 0
