"""Regenerate the synthetic placeholder receipts and sample predictions in this directory."""

import copy
import json
from pathlib import Path

HERE = Path(__file__).parent


def make(store, day, items, discounts, tax):
    subtotal = sum(p for _, p in items) - sum(discounts)
    return {
        "store": store,
        "purchased_on": day,
        "synthetic": True,
        "items": [
            {"name": n, "quantity": 1, "unit_price_cents": p, "total_cents": p} for n, p in items
        ],
        "discounts": [{"description": "COUPON", "amount_cents": a} for a in discounts],
        "subtotal_cents": subtotal,
        "tax_cents": tax,
        "total_cents": subtotal + tax,
    }


def main() -> None:
    truth = {
        "synthetic-001": make(
            "Sample Grocer",
            "2026-01-15",
            [("WHOLE MILK 1GAL", 429), ("BANANAS", 148), ("SOURDOUGH LOAF", 550)],
            [100],
            72,
        ),
        "synthetic-002": make(
            "Corner Market",
            "2026-02-03",
            [("EGGS DOZEN", 389), ("CHEDDAR BLOCK", 699), ("APPLES", 312), ("PASTA", 179)],
            [],
            0,
        ),
        "synthetic-003": make(
            "Sample Grocer",
            "2026-02-20",
            [("COFFEE BEANS", 1299), ("OAT MILK", 449)],
            [200],
            95,
        ),
        "synthetic-004": make(
            "Corner Market",
            "2026-03-01",
            [("RICE 5LB", 849), ("TOMATOES", 276), ("YOGURT", 125)],
            [],
            38,
        ),
    }
    predictions = copy.deepcopy(truth)
    predictions["synthetic-002"]["items"][1]["total_cents"] = 669
    predictions["synthetic-002"]["total_cents"] = 1549
    predictions["synthetic-003"]["items"][0]["name"] = "COFFEE BEAN"
    del predictions["synthetic-004"]

    for folder, receipts in (("receipts", truth), ("predictions", predictions)):
        (HERE / folder).mkdir(exist_ok=True)
        for name, body in receipts.items():
            (HERE / folder / f"{name}.json").write_text(json.dumps(body, indent=2) + "\n")


if __name__ == "__main__":
    main()
