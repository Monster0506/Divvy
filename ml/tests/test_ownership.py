import ast
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from divvy_ml.app import app
from divvy_ml.ownership import ItemPrediction, PredictResponse

client = TestClient(app)

PACKAGE = Path(__file__).resolve().parent.parent / "divvy_ml"

REQUEST = {
    "household_id": "h1",
    "people": [{"id": "p1", "name": "Alex"}, {"id": "p2", "name": "Sam"}],
    "items": [
        {"item_id": "i1", "description": "WHOLE MILK 1GAL"},
        {"item_id": "i2", "description": "BANANAS"},
    ],
}


def test_predict_returns_unknown_for_every_item():
    response = client.post("/ownership/predict", json=REQUEST)
    assert response.status_code == 200
    body = PredictResponse.model_validate(response.json())
    assert [p.item_id for p in body.predictions] == ["i1", "i2"]
    for prediction in body.predictions:
        assert prediction.status == "unknown"
        assert prediction.owners == []
        assert prediction.alternatives == []


def test_predict_accepts_empty_item_list():
    response = client.post("/ownership/predict", json={**REQUEST, "items": []})
    assert response.status_code == 200
    assert response.json()["predictions"] == []


@pytest.mark.parametrize(
    "overrides",
    [
        {"people": []},
        {"people": [{"id": "p1", "name": "A"}, {"id": "p1", "name": "B"}]},
        {"items": [{"item_id": "i1", "description": "A"}, {"item_id": "i1", "description": "B"}]},
        {"household_id": ""},
        {"extra": True},
    ],
)
def test_predict_rejects_invalid_requests(overrides):
    response = client.post("/ownership/predict", json={**REQUEST, **overrides})
    assert response.status_code == 422


@pytest.mark.parametrize(
    "fields",
    [
        {"status": "unknown", "owners": ["p1"], "confidence": 0},
        {"status": "predicted", "owners": [], "confidence": 0.9},
        {"status": "predicted", "owners": ["p1"], "confidence": 1.5},
    ],
)
def test_inconsistent_predictions_are_rejected(fields):
    with pytest.raises(ValidationError):
        ItemPrediction(item_id="i1", **fields)


def _imported_modules(module_dir: Path) -> set[str]:
    names = set()
    for path in module_dir.rglob("*.py"):
        for node in ast.walk(ast.parse(path.read_text())):
            if isinstance(node, ast.Import):
                names.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                names.add(node.module)
    return names


@pytest.mark.parametrize(
    ("module", "other"), [("ownership", "receipts"), ("receipts", "ownership")]
)
def test_receipts_and_ownership_do_not_import_each_other(module, other):
    imported = _imported_modules(PACKAGE / module)
    assert not any(name.startswith(f"divvy_ml.{other}") for name in imported)
