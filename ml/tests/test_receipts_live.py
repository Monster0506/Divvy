"""Calls the real vision service. Skipped unless a key and an image are provided.

QWEN_API_KEY_FILE=../secrets.txt DIVVY_LIVE_RECEIPT=receipt.jpg
pytest tests/test_receipts_live.py -s
"""

import os
import time
from pathlib import Path

import pytest

from divvy_ml.qwen_config import NotConfiguredError, QwenConfig
from divvy_ml.receipts import images
from divvy_ml.receipts.qwen import QwenExtractor

RECEIPT = os.environ.get("DIVVY_LIVE_RECEIPT")

pytestmark = pytest.mark.skipif(not RECEIPT, reason="set DIVVY_LIVE_RECEIPT to run")


def test_a_real_receipt_is_extracted_within_the_time_budget():
    try:
        config = QwenConfig.from_env()
    except NotConfiguredError as exc:
        pytest.skip(str(exc))
    image = images.from_bytes(Path(RECEIPT).read_bytes())

    started = time.monotonic()
    receipt = QwenExtractor(config).extract(image)
    elapsed = time.monotonic() - started

    print(f"{elapsed:.1f}s, {len(receipt.items)} items, total {receipt.total_cents}")
    print("warnings:", receipt.warnings)
    assert receipt.items
    assert receipt.total_cents is not None
    assert elapsed < 30
