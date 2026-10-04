import json
from pathlib import Path

import pytest

from divvy_ml.eval.extraction import reconciles
from divvy_ml.receipts import ExtractedReceipt
from divvy_ml.receipts.validation import check_receipt

PATHS = sorted((Path(__file__).parent / "data" / "labelled").glob("*.json"))


@pytest.mark.parametrize("path", PATHS, ids=lambda p: p.stem)
def test_a_labelled_receipt_is_valid_and_adds_up(path):
    receipt = ExtractedReceipt.model_validate(json.loads(path.read_text(encoding="utf-8")))

    assert receipt.items
    assert reconciles(receipt)
    assert check_receipt(receipt) == []
