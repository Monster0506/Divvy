import json
from datetime import date
from pathlib import Path

import httpx
import pytest

from divvy_ml.qwen_config import NotConfiguredError, QwenConfig
from divvy_ml.receipts.dates import normalise_date
from divvy_ml.receipts.images import ReceiptImage
from divvy_ml.receipts.qwen import ExtractionError, QwenExtractor, cents, to_receipt
from divvy_ml.receipts.validation import check_receipt

FIXTURES = Path(__file__).parent / "data" / "qwen"
KEY = "sk-test-secret-key"
IMAGE = ReceiptImage(b"\x89PNG\r\n\x1a\n\x00\x00", "image/png")


def fixture(name: str) -> dict:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def reply(data) -> httpx.Response:
    content = data if isinstance(data, str) else json.dumps(data)
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def extractor(handler, **config) -> tuple[QwenExtractor, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    client = httpx.Client(transport=httpx.MockTransport(record))
    return QwenExtractor(QwenConfig(api_key=KEY, **config), client), seen


# --- what is sent ---------------------------------------------------------------------------


def test_the_request_targets_the_configured_endpoint_with_the_key():
    qwen, seen = extractor(lambda r: reply(fixture("walmart_two_items_no_tax")))

    qwen.extract(IMAGE)

    request = seen[0]
    assert str(request.url) == "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
    assert request.headers["authorization"] == f"Bearer {KEY}"


def test_the_body_asks_for_strict_json_with_thinking_and_the_image():
    qwen, seen = extractor(lambda r: reply(fixture("walmart_two_items_no_tax")))

    qwen.extract(IMAGE)

    body = json.loads(seen[0].content)
    assert body["model"] == "qwen3.7-plus"
    assert body["enable_thinking"] is True
    assert body["thinking_budget"] == 512
    assert body["response_format"]["type"] == "json_schema"
    assert body["response_format"]["json_schema"]["strict"] is True
    user = body["messages"][1]["content"]
    assert user[1]["image_url"]["url"].startswith("data:image/png;base64,")


def test_model_budget_and_thinking_can_be_configured():
    qwen, seen = extractor(
        lambda r: reply(fixture("walmart_two_items_no_tax")),
        model="qwen-other",
        enable_thinking=False,
        thinking_budget=64,
    )

    qwen.extract(IMAGE)

    body = json.loads(seen[0].content)
    assert (body["model"], body["enable_thinking"], body["thinking_budget"]) == (
        "qwen-other",
        False,
        64,
    )


def test_the_prompt_asks_for_transcription_only():
    qwen, seen = extractor(lambda r: reply(fixture("walmart_two_items_no_tax")))

    qwen.extract(IMAGE)

    system = json.loads(seen[0].content)["messages"][0]["content"]
    assert "rather than calculating them" in system
    assert "Do not infer product identities" in system


# --- the receipt-bot fixtures -----------------------------------------------------------------


def test_a_receipt_with_no_tax_converts_to_cents_and_reconciles():
    qwen, _ = extractor(lambda r: reply(fixture("walmart_two_items_no_tax")))

    receipt = qwen.extract(IMAGE)

    assert receipt.store == "Walmart"
    assert [i.normalized_name for i in receipt.items] == ["great value 2% milk", "white bread"]
    assert receipt.purchased_on == date(2026, 8, 12)
    assert [(i.name, i.total_cents, i.unit_price_cents) for i in receipt.items] == [
        ("GV 2% MILK GAL", 398, 398),
        ("WHT BREAD", 250, 250),
    ]
    assert (receipt.subtotal_cents, receipt.tax_cents, receipt.total_cents) == (648, None, 648)
    assert receipt.warnings == []


def test_a_receipt_with_tax_reconciles():
    qwen, _ = extractor(lambda r: reply(fixture("walmart_five_items_with_tax")))

    receipt = qwen.extract(IMAGE)

    assert len(receipt.items) == 5
    assert [i.quantity for i in receipt.items] == [1, 1, 1, 2, 1]
    assert (receipt.subtotal_cents, receipt.tax_cents, receipt.total_cents) == (5418, 379, 5797)
    assert receipt.warnings == []


def test_duplicate_lines_stay_separate():
    qwen, _ = extractor(lambda r: reply(fixture("walmart_duplicate_items")))

    receipt = qwen.extract(IMAGE)

    assert [i.name for i in receipt.items].count("IC TRAY W/LD") == 2
    assert receipt.warnings == []


# --- failures ---------------------------------------------------------------------------------


@pytest.mark.parametrize("status", [401, 429, 500])
def test_an_error_status_is_an_extraction_error_without_the_key(status):
    qwen, _ = extractor(lambda r: httpx.Response(status, text=f"nope {KEY}"))

    with pytest.raises(ExtractionError) as caught:
        qwen.extract(IMAGE)

    assert str(status) in str(caught.value)
    assert KEY not in str(caught.value)


def test_a_timeout_is_an_extraction_error():
    def slow(request):
        raise httpx.ReadTimeout("slow", request=request)

    qwen, _ = extractor(slow)

    with pytest.raises(ExtractionError, match="could not be reached"):
        qwen.extract(IMAGE)


@pytest.mark.parametrize("content", ["", "not json", "42", '{"items": "x"}'])
def test_an_answer_that_is_not_a_receipt_is_an_extraction_error(content):
    qwen, _ = extractor(lambda r: reply(content))

    with pytest.raises(ExtractionError):
        qwen.extract(IMAGE)


def test_a_body_with_no_choices_is_an_extraction_error():
    qwen, _ = extractor(lambda r: httpx.Response(200, json={}))

    with pytest.raises(ExtractionError):
        qwen.extract(IMAGE)


def test_a_single_item_list_is_unwrapped():
    qwen, _ = extractor(lambda r: reply([fixture("walmart_two_items_no_tax")]))

    assert len(qwen.extract(IMAGE).items) == 2


# --- conversion -------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("dollars", "expected"),
    [(4.29, 429), (0.1, 10), (2.675, 268), (0, 0), (-1.5, -150), (1e-3, 0), (None, None)],
)
def test_dollars_become_cents_from_the_printed_decimal(dollars, expected):
    assert cents(dollars) == expected


def test_missing_quantity_defaults_to_one_and_bad_unit_prices_are_dropped():
    receipt = to_receipt(
        {
            "merchant": " Corner Shop ",
            "date": "2026-02-03",
            "items": [
                {"name": "APPLES", "quantity": None, "unit_price": -1.0, "total_price": 3.0},
                {"name": "  ", "quantity": 1, "unit_price": 1.0, "total_price": 1.0},
            ],
            "subtotal": 3.0,
            "tax": None,
            "total": 3.0,
        }
    )

    assert receipt.store == "Corner Shop"
    assert receipt.items[0].quantity == 1
    assert receipt.items[0].unit_price_cents is None
    assert len(receipt.items) == 1
    assert any("no item text" in w for w in receipt.warnings)


def test_a_coupon_line_keeps_its_negative_total():
    receipt = to_receipt(
        {
            "merchant": "Shop",
            "date": None,
            "items": [
                {"name": "CEREAL", "quantity": 1, "unit_price": 5.0, "total_price": 5.0},
                {"name": "COUPON", "quantity": None, "unit_price": None, "total_price": -1.0},
            ],
            "subtotal": 4.0,
            "tax": 0.0,
            "total": 4.0,
        }
    )

    assert [i.total_cents for i in receipt.items] == [500, -100]
    assert receipt.warnings == []


# --- validation and dates ---------------------------------------------------------------------


def test_arithmetic_mismatches_become_warnings_and_change_nothing():
    receipt = to_receipt(
        {
            "merchant": "Shop",
            "date": "2026-02-03",
            "items": [{"name": "A", "quantity": 2, "unit_price": 1.0, "total_price": 5.0}],
            "subtotal": 4.0,
            "tax": 0.5,
            "total": 9.0,
        }
    )

    assert len(receipt.warnings) == 3
    assert any("quantity x unit price is 2.00" in w for w in receipt.warnings)
    assert any("Items less discounts come to 5.00" in w for w in receipt.warnings)
    assert any("Subtotal plus tax is 4.50" in w for w in receipt.warnings)
    assert receipt.total_cents == 900 and receipt.items[0].total_cents == 500


def test_one_cent_of_rounding_is_tolerated():
    receipt = to_receipt(
        {
            "merchant": "Shop",
            "date": "2026-02-03",
            "items": [{"name": "A", "quantity": 3, "unit_price": 0.333, "total_price": 1.0}],
            "subtotal": 1.0,
            "tax": 0.07,
            "total": 1.07,
        }
    )

    assert check_receipt(receipt) == []


def test_total_is_checked_against_items_when_there_is_no_subtotal():
    receipt = to_receipt(
        {
            "merchant": "Shop",
            "date": "2026-02-03",
            "items": [{"name": "A", "quantity": 1, "unit_price": 2.0, "total_price": 2.0}],
            "subtotal": None,
            "tax": None,
            "total": 3.0,
        }
    )

    assert any("total is 3.00" in w for w in receipt.warnings)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("08/20/26", date(2026, 8, 20)),
        ("08/20/2026", date(2026, 8, 20)),
        ("2026-08-20", date(2026, 8, 20)),
        ("Aug 20, 2026", date(2026, 8, 20)),
        ("20 August 2026", date(2026, 8, 20)),
        ("  08-20-26 ", date(2026, 8, 20)),
        ("13/45/26", None),
        ("yesterday", None),
        ("", None),
        (None, None),
    ],
)
def test_printed_dates_are_normalised_or_left_empty(raw, expected):
    assert normalise_date(raw) == expected


def test_an_unrecognised_date_is_null_with_a_warning():
    receipt = to_receipt(
        {
            "merchant": "Shop",
            "date": "sometime",
            "items": [],
            "subtotal": None,
            "tax": None,
            "total": None,
        }
    )

    assert receipt.purchased_on is None
    assert any("'sometime' was not recognised" in w for w in receipt.warnings)


# --- config -----------------------------------------------------------------------------------


def test_config_defaults_to_the_maas_endpoint_and_reads_overrides():
    config = QwenConfig.from_env(
        {
            "QWEN_API_KEY": KEY,
            "QWEN_BASE_URL": "https://example.test/v1/",
            "QWEN_ENABLE_THINKING": "off",
        }
    )

    assert config.base_url == "https://example.test/v1"
    assert config.enable_thinking is False
    assert QwenConfig.from_env({"QWEN_API_KEY": KEY}).base_url == (
        "https://maas.qwencloudapi.com/compatible-mode/v1"
    )


def test_the_key_can_come_from_a_file_holding_only_the_key(tmp_path):
    key_file = tmp_path / "secrets.txt"
    key_file.write_text(f"{KEY}\n", encoding="utf-8")

    assert QwenConfig.from_env({"QWEN_API_KEY_FILE": str(key_file)}).api_key == KEY


def test_no_key_and_an_unreadable_file_are_not_configured(tmp_path):
    with pytest.raises(NotConfiguredError):
        QwenConfig.from_env({})
    with pytest.raises(NotConfiguredError, match="QWEN_API_KEY_FILE"):
        QwenConfig.from_env({"QWEN_API_KEY_FILE": str(tmp_path / "missing.txt")})
    empty = tmp_path / "empty.txt"
    empty.write_text("\n", encoding="utf-8")
    with pytest.raises(NotConfiguredError):
        QwenConfig.from_env({"QWEN_API_KEY_FILE": str(empty)})


def test_the_key_is_not_shown_when_the_config_is_printed():
    assert KEY not in repr(QwenConfig(api_key=KEY))
