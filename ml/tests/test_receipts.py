import base64

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from divvy_ml.app import app
from divvy_ml.receipts import ExtractedItem, ExtractedReceipt
from divvy_ml.receipts.images import ReceiptImage
from divvy_ml.receipts.qwen import ExtractionError
from divvy_ml.receipts.router import _configured_extractor, get_extractor

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
PNG_B64 = base64.b64encode(PNG).decode()

SAMPLE = ExtractedReceipt(
    store="Sample Grocer",
    items=[
        ExtractedItem(name="WHOLE MILK 1GAL", quantity=1, unit_price_cents=429, total_cents=429)
    ],
    total_cents=429,
)


class FakeExtractor:
    def __init__(self, result=SAMPLE, error=None):
        self.result, self.error, self.seen = result, error, []

    def extract(self, image: ReceiptImage) -> ExtractedReceipt:
        self.seen.append(image)
        if self.error:
            raise self.error
        return self.result


@pytest.fixture
def extractor():
    fake = FakeExtractor()
    app.dependency_overrides[get_extractor] = lambda: fake
    yield fake
    app.dependency_overrides.clear()


client = TestClient(app)


def test_extract_returns_the_extractors_receipt(extractor):
    response = client.post("/receipts/extract", json={"image_base64": PNG_B64})

    assert response.status_code == 200
    assert ExtractedReceipt.model_validate(response.json()) == SAMPLE
    assert extractor.seen[0].data == PNG
    assert extractor.seen[0].mime_type == "image/png"


def test_extract_money_fields_are_integer_cents(extractor):
    body = client.post("/receipts/extract", json={"image_base64": PNG_B64}).json()

    assert isinstance(body["total_cents"], int)
    assert all(isinstance(item["total_cents"], int) for item in body["items"])


def test_extract_reads_a_stored_image_by_id(extractor, tmp_path, monkeypatch):
    image_id = "0b9a4a1e-6f5c-4a52-9a45-0c1e2f3a4b5c"
    (tmp_path / image_id).write_bytes(PNG)
    monkeypatch.setenv("DIVVY_IMAGE_DIR", str(tmp_path))

    response = client.post("/receipts/extract", json={"image_id": image_id})

    assert response.status_code == 200
    assert extractor.seen[0].data == PNG


@pytest.mark.parametrize(
    "image_id", ["../secrets", "abc123", "0B9A4A1E-6F5C-4A52-9A45-0C1E2F3A4B5C"]
)
def test_extract_rejects_ids_that_are_not_canonical_uuids(
    extractor, tmp_path, monkeypatch, image_id
):
    monkeypatch.setenv("DIVVY_IMAGE_DIR", str(tmp_path))

    assert client.post("/receipts/extract", json={"image_id": image_id}).status_code == 422
    assert extractor.seen == []


def test_extract_by_id_without_an_image_dir_is_422(extractor, monkeypatch):
    monkeypatch.delenv("DIVVY_IMAGE_DIR", raising=False)

    response = client.post(
        "/receipts/extract", json={"image_id": "0b9a4a1e-6f5c-4a52-9a45-0c1e2f3a4b5c"}
    )

    assert response.status_code == 422


def test_extract_an_unknown_id_is_422(extractor, tmp_path, monkeypatch):
    monkeypatch.setenv("DIVVY_IMAGE_DIR", str(tmp_path))

    response = client.post(
        "/receipts/extract", json={"image_id": "0b9a4a1e-6f5c-4a52-9a45-0c1e2f3a4b5c"}
    )

    assert response.status_code == 422


@pytest.mark.parametrize(
    "payload",
    [
        {"image_base64": "not base64!!"},
        {"image_base64": base64.b64encode(b"hello").decode()},
        {"image_base64": base64.b64encode(b"").decode() or "="},
    ],
)
def test_extract_rejects_unusable_images(extractor, payload):
    assert client.post("/receipts/extract", json=payload).status_code == 422
    assert extractor.seen == []


@pytest.mark.parametrize(
    "payload",
    [{}, {"image_id": "a", "image_base64": PNG_B64}, {"image_id": ""}, {"url": "x"}],
)
def test_extract_rejects_invalid_requests(extractor, payload):
    response = client.post("/receipts/extract", json=payload)
    assert response.status_code == 422


def test_a_failing_vision_service_is_502(extractor):
    extractor.error = ExtractionError("the vision service answered 500")

    response = client.post("/receipts/extract", json={"image_base64": PNG_B64})

    assert response.status_code == 502
    assert "500" in response.json()["detail"]


def test_without_an_api_key_extraction_is_503(monkeypatch):
    _configured_extractor.cache_clear()
    monkeypatch.delenv("QWEN_API_KEY", raising=False)
    monkeypatch.delenv("QWEN_API_KEY_FILE", raising=False)

    response = client.post("/receipts/extract", json={"image_base64": PNG_B64})

    assert response.status_code == 503
    assert "QWEN_API_KEY" in response.json()["detail"]


def test_fractional_cents_are_rejected():
    with pytest.raises(ValidationError):
        ExtractedItem(name="MILK", total_cents=4.29)
