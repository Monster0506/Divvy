"""Receipt extraction with a Qwen vision model over an OpenAI-compatible chat API.

The model only transcribes what is printed. Totals are never asked for as a calculation, and the
arithmetic checks run afterwards in validation.py.
"""

import json
import logging
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

import httpx
from pydantic import ValidationError

from divvy_ml.normalize import NameNormalizer, RulesNormalizer, normalize_product_name
from divvy_ml.qwen_config import QwenConfig
from divvy_ml.receipts.dates import normalise_date
from divvy_ml.receipts.images import ReceiptImage
from divvy_ml.receipts.schemas import ExtractedItem, ExtractedReceipt
from divvy_ml.receipts.validation import check_receipt

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are extracting structured data from a receipt.

Rules:

1. Only report information that is visible or directly derivable from the receipt.
2. Never guess missing information.
3. Preserve receipt abbreviations and wording in item names.
4. Do not infer product identities from outside knowledge.
5. If a value cannot be determined, use null where the schema permits it.
6. Do not invent quantities or unit prices.
7. Preserve duplicate line items as separate items unless the receipt explicitly combines them.
8. Report coupons, discounts and refunds as their own lines with a negative total_price.
9. Transcribe the date as it appears on the receipt. Do not convert its format.
10. Transcribe subtotal, tax and total from the receipt rather than calculating them.
11. Do not use information from the surrounding photograph that is not part of the receipt.
12. Do not provide explanations or reasoning. Return only the requested structured data."""

_NUMBER = {"type": ["number", "null"]}

RECEIPT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "merchant": {"type": ["string", "null"], "description": "Store name as printed."},
        "date": {"type": ["string", "null"], "description": "Transaction date as printed."},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Item text as printed."},
                    "quantity": {**_NUMBER, "description": "Explicit quantity, else null."},
                    "unit_price": {**_NUMBER, "description": "Explicit unit price, else null."},
                    "total_price": {"type": "number", "description": "Price of this line."},
                },
                "required": ["name", "quantity", "unit_price", "total_price"],
                "additionalProperties": False,
            },
        },
        "subtotal": _NUMBER,
        "tax": _NUMBER,
        "total": _NUMBER,
    },
    "required": ["merchant", "date", "items", "subtotal", "tax", "total"],
    "additionalProperties": False,
}


class ExtractionError(RuntimeError):
    """The vision service failed or returned something that is not a receipt."""


def cents(amount: float | int | None) -> int | None:
    """Dollars to integer cents, rounding half up on the printed decimal."""
    if amount is None:
        return None
    return int((Decimal(str(amount)) * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def to_receipt(data: dict[str, Any]) -> ExtractedReceipt:
    """Maps the model's dollar-based answer to the cents-based contract and adds warnings."""
    warnings: list[str] = []
    items: list[ExtractedItem] = []
    for line in data.get("items") or []:
        name = str(line.get("name") or "").strip()
        if not name:
            warnings.append("A line with no item text was skipped")
            continue
        quantity = line.get("quantity")
        unit = cents(line.get("unit_price"))
        items.append(
            ExtractedItem(
                name=name,
                normalized_name=normalize_product_name(name) or None,
                quantity=quantity if isinstance(quantity, int | float) and quantity > 0 else 1,
                unit_price_cents=unit if unit is not None and unit >= 0 else None,
                total_cents=cents(line.get("total_price")) or 0,
            )
        )

    raw_date = (data.get("date") or "").strip() or None
    purchased_on = normalise_date(raw_date)
    if raw_date and purchased_on is None:
        warnings.append(f"The date {raw_date!r} was not recognised")

    tax = cents(data.get("tax"))
    receipt = ExtractedReceipt(
        store=(data.get("merchant") or "").strip() or None,
        purchased_on=purchased_on,
        items=items,
        subtotal_cents=cents(data.get("subtotal")),
        tax_cents=tax if tax is not None and tax >= 0 else None,
        total_cents=cents(data.get("total")),
        warnings=warnings,
    )
    receipt.warnings.extend(check_receipt(receipt))
    return receipt


class QwenExtractor:
    def __init__(
        self,
        config: QwenConfig,
        client: httpx.Client | None = None,
        normalizer: NameNormalizer | None = None,
    ) -> None:
        self._config = config
        self._normalizer = normalizer or RulesNormalizer()
        self._client = client or httpx.Client(timeout=config.timeout_seconds)

    def extract(self, image: ReceiptImage) -> ExtractedReceipt:
        data = self._ask(image)
        try:
            receipt = to_receipt(data)
        except (AttributeError, TypeError, ValueError, ArithmeticError, ValidationError):
            raise ExtractionError("the vision service did not return a receipt") from None
        names = self._normalizer.normalize_many([item.name for item in receipt.items])
        receipt.items = [
            item.model_copy(update={"normalized_name": name or None})
            for item, name in zip(receipt.items, names, strict=True)
        ]
        return receipt

    def _ask(self, image: ReceiptImage) -> dict[str, Any]:
        config = self._config
        body = {
            "model": config.model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Extract the receipt data from this image."},
                        {"type": "image_url", "image_url": {"url": image.data_url}},
                    ],
                },
            ],
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "receipt", "schema": RECEIPT_SCHEMA, "strict": True},
            },
            "enable_thinking": config.enable_thinking,
            "thinking_budget": config.thinking_budget,
        }
        try:
            response = self._client.post(
                f"{config.base_url}/chat/completions",
                json=body,
                headers={"Authorization": f"Bearer {config.api_key}"},
            )
        except httpx.HTTPError as exc:
            raise ExtractionError(
                f"the vision service could not be reached ({type(exc).__name__})"
            ) from None
        if response.status_code >= 400:
            log.warning("Vision service answered %s", response.status_code)
            raise ExtractionError(f"the vision service answered {response.status_code}")
        return _parse(response)


def _parse(response: httpx.Response) -> dict[str, Any]:
    try:
        content = response.json()["choices"][0]["message"]["content"]
        data = json.loads(content)
    except (ValueError, KeyError, IndexError, TypeError):
        raise ExtractionError("the vision service did not return a receipt") from None
    if isinstance(data, list) and len(data) == 1:
        data = data[0]
    if not isinstance(data, dict):
        raise ExtractionError("the vision service did not return a receipt")
    return data
