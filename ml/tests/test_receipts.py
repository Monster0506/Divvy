import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from divvy_ml.app import app
from divvy_ml.receipts import ExtractedItem, ExtractedReceipt

client = TestClient(app)


def test_extract_returns_schema_valid_receipt():
    response = client.post("/receipts/extract", json={"image_id": "abc123"})
    assert response.status_code == 200
    receipt = ExtractedReceipt.model_validate(response.json())
    assert receipt.items


def test_extract_money_fields_are_integer_cents():
    body = client.post("/receipts/extract", json={"image_id": "abc123"}).json()
    for key in ("subtotal_cents", "tax_cents", "total_cents"):
        assert isinstance(body[key], int)
    for item in body["items"]:
        assert isinstance(item["total_cents"], int)
    for discount in body["discounts"]:
        assert isinstance(discount["amount_cents"], int)


def test_extract_accepts_base64_image():
    response = client.post("/receipts/extract", json={"image_base64": "aGVsbG8="})
    assert response.status_code == 200


@pytest.mark.parametrize(
    "payload",
    [{}, {"image_id": "a", "image_base64": "aGVsbG8="}, {"image_id": ""}, {"url": "x"}],
)
def test_extract_rejects_invalid_requests(payload):
    response = client.post("/receipts/extract", json=payload)
    assert response.status_code == 422


def test_fractional_cents_are_rejected():
    with pytest.raises(ValidationError):
        ExtractedItem(name="MILK", total_cents=4.29)
