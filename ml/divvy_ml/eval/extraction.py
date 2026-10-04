"""Score receipt extraction against labelled ground truth.

Ground truth is one JSON per receipt (ExtractedReceipt shape) in a directory; predictions use the
same file names in another. Reports item price accuracy and total reconciliation rate.
"""

import argparse
import json
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from divvy_ml.eval.dataset import normalize_item
from divvy_ml.receipts.schemas import ExtractedReceipt

DEFAULT_TRUTH_DIR = Path("tests/data/receipts")


@dataclass(frozen=True)
class ReceiptScore:
    name: str
    items_expected: int
    items_correct: int
    reconciles: bool
    total_correct: bool


@dataclass(frozen=True)
class ExtractionReport:
    scores: list[ReceiptScore]

    @property
    def item_price_accuracy(self) -> float:
        expected = sum(s.items_expected for s in self.scores)
        return sum(s.items_correct for s in self.scores) / expected if expected else 0.0

    @property
    def reconciliation_rate(self) -> float:
        return _rate(s.reconciles for s in self.scores)

    @property
    def total_accuracy(self) -> float:
        return _rate(s.total_correct for s in self.scores)


def _rate(flags: Iterable[bool]) -> float:
    flags = list(flags)
    return sum(flags) / len(flags) if flags else 0.0


def load_receipt(path: Path) -> ExtractedReceipt:
    return ExtractedReceipt.model_validate(json.loads(path.read_text(encoding="utf-8")))


def reconciles(receipt: ExtractedReceipt) -> bool:
    """True when items minus discounts plus tax equals the printed total, to the cent."""
    if receipt.total_cents is None or not receipt.items:
        return False
    items = sum(item.total_cents for item in receipt.items)
    discounts = sum(d.amount_cents for d in receipt.discounts)
    return items - discounts + (receipt.tax_cents or 0) == receipt.total_cents


def count_correct_items(truth: ExtractedReceipt, predicted: ExtractedReceipt) -> int:
    """Items matched by normalized name, one-to-one, whose line total equals the label."""
    available: dict[str, list[int]] = {}
    for item in predicted.items:
        available.setdefault(normalize_item(item.name), []).append(item.total_cents)
    correct = 0
    for item in truth.items:
        prices = available.get(normalize_item(item.name), [])
        if item.total_cents in prices:
            prices.remove(item.total_cents)
            correct += 1
    return correct


def score_receipt(
    name: str, truth: ExtractedReceipt, predicted: ExtractedReceipt | None
) -> ReceiptScore:
    if predicted is None:
        return ReceiptScore(name, len(truth.items), 0, False, False)
    return ReceiptScore(
        name=name,
        items_expected=len(truth.items),
        items_correct=count_correct_items(truth, predicted),
        reconciles=reconciles(predicted),
        total_correct=truth.total_cents is not None and predicted.total_cents == truth.total_cents,
    )


def evaluate(truth_dir: Path, predictions_dir: Path) -> ExtractionReport:
    """Score every labelled receipt; a missing or invalid prediction scores as a failure."""
    scores = []
    for path in sorted(truth_dir.glob("*.json")):
        truth = load_receipt(path)
        try:
            predicted = load_receipt(predictions_dir / path.name)
        except (OSError, ValueError):
            predicted = None
        scores.append(score_receipt(path.stem, truth, predicted))
    return ExtractionReport(scores)


def format_report(report: ExtractionReport) -> str:
    lines = [f"receipts evaluated: {len(report.scores)}"]
    for s in report.scores:
        lines.append(
            f"  {s.name}: items {s.items_correct}/{s.items_expected}, "
            f"reconciles={s.reconciles}, total_correct={s.total_correct}"
        )
    lines.append(f"item price accuracy: {report.item_price_accuracy:.1%}")
    lines.append(f"total reconciliation rate: {report.reconciliation_rate:.1%}")
    lines.append(f"total accuracy: {report.total_accuracy:.1%}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="evaluate_extraction", description=__doc__)
    parser.add_argument("--predictions", type=Path, required=True, help="dir of predicted JSON")
    parser.add_argument("--truth", type=Path, default=DEFAULT_TRUTH_DIR)
    args = parser.parse_args(argv)

    if not args.truth.is_dir() or not any(args.truth.glob("*.json")):
        parser.error(f"no ground-truth receipts in {args.truth}")
    print(format_report(evaluate(args.truth, args.predictions)))


if __name__ == "__main__":
    main()
