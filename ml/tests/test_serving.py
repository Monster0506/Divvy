import json
import time

import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.embed import make_embedder
from divvy_ml.ownership.manager import ModelManager
from divvy_ml.ownership.router import get_manager, get_store
from divvy_ml.ownership.store import HistoryStore
from divvy_ml.synthetic import generate

PEOPLE = ("sumedh", "savar", "andrew", "tj")
EMBEDDER = make_embedder("hashing")


def push_synthetic(client, household, size, seed=7):
    """Send a synthetic household's history through the real API."""
    rows = []
    for p in generate(size, seed):
        record = p.to_json()
        rows.append(
            {
                "observation_id": f"obs-{record['seq']}",
                "purchased_on": record["date"],
                "description": record["item"],
                "member_ids": list(PEOPLE),
                "owner_ids": [name for name, owns in record["owners"].items() if owns],
            }
        )

    for start in range(0, len(rows), 1000):
        response = client.put(
            f"/ownership/households/{household}/observations",
            json={"observations": rows[start : start + 1000]},
        )
        assert response.status_code == 200


def request(household, items, people=PEOPLE):
    return {
        "household_id": household,
        "people": [{"id": p, "name": p.title()} for p in people],
        "items": [{"item_id": f"i{n}", "description": d} for n, d in enumerate(items)],
    }


def predict(client, household, items, people=PEOPLE):
    response = client.post("/ownership/predict", json=request(household, items, people))
    assert response.status_code == 200, response.text
    return response.json()


def by_item(body):
    return {p["item_id"]: p for p in body["predictions"]}


@pytest.fixture
def setup(tmp_path):
    store = HistoryStore(tmp_path / "history")
    manager = ModelManager(store, EMBEDDER, "knn-calibrated", models_dir=tmp_path / "models")
    app.dependency_overrides[get_store] = lambda: store
    app.dependency_overrides[get_manager] = lambda: manager
    yield TestClient(app), store, manager, tmp_path
    app.dependency_overrides.clear()


@pytest.fixture
def client(setup):
    return setup[0]


def test_a_household_with_no_history_gets_unknown_for_everything(client):
    body = predict(client, "empty", ["Coke Zero 12-pack", "Paper towels"])

    assert body["model_version"] == "knn-calibrated-untrained"
    for prediction in body["predictions"]:
        assert prediction["status"] == "unknown"
        assert prediction["owners"] == []
        assert prediction["confidence"] == 0


def test_a_household_with_too_little_history_is_still_unknown(client):
    push_synthetic(client, "thin", 20)

    body = predict(client, "thin", ["Coke Zero 12-pack"])

    assert body["predictions"][0]["status"] == "unknown"
    assert body["model_version"].endswith("-untrained")


def test_known_items_are_predicted_with_owners_and_confidence(client):
    push_synthetic(client, "rich", 900)

    body = predict(
        client, "rich", ["Coke Zero 12-pack", "Paper towels", "Bounty paper towels 6 rolls"]
    )
    got = by_item(body)

    assert got["i0"]["status"] == "predicted"
    assert got["i0"]["owners"] == ["tj"]
    assert got["i0"]["confidence"] > 0.8
    assert sorted(got["i1"]["owners"]) == sorted(PEOPLE)
    assert got["i2"]["status"] == "predicted"


def test_something_nothing_resembles_is_unknown(client):
    push_synthetic(client, "rich", 600)

    body = predict(client, "rich", ["zqxv wjkp mnbh"])

    assert body["predictions"][0]["status"] == "unknown"
    assert body["predictions"][0]["owners"] == []


def test_the_model_version_is_in_every_response_and_changes_with_the_history(client):
    push_synthetic(client, "h1", 400)
    first = predict(client, "h1", ["Eggs"])["model_version"]
    again = predict(client, "h1", ["Eggs"])["model_version"]

    push_synthetic(client, "h1", 450, seed=8)
    later = predict(client, "h1", ["Eggs"])["model_version"]

    assert first == again
    assert first.startswith("knn-calibrated-400-")
    assert later != first


def test_a_correction_is_reflected_in_the_next_prediction(client):
    push_synthetic(client, "h1", 300)
    base = {
        "purchased_on": "2030-01-01",
        "description": "Zorblax fizz juice",
        "member_ids": list(PEOPLE),
    }
    rows = [{**base, "observation_id": f"z{n}", "owner_ids": ["sumedh"]} for n in range(6)]
    client.put("/ownership/households/h1/observations", json={"observations": rows})
    assert predict(client, "h1", ["Zorblax fizz juice"])["predictions"][0]["owners"] == ["sumedh"]

    corrected = [{**row, "owner_ids": ["tj"]} for row in rows]
    client.put("/ownership/households/h1/observations", json={"observations": corrected})

    assert predict(client, "h1", ["Zorblax fizz juice"])["predictions"][0]["owners"] == ["tj"]


def test_archived_people_are_left_out_of_predictions(client):
    push_synthetic(client, "h1", 600)
    current = ("sumedh", "savar", "andrew")

    body = predict(client, "h1", ["Coke Zero 12-pack", "Paper towels", "Frozen pizza"], current)

    for prediction in body["predictions"]:
        assert "tj" not in prediction["owners"]
        assert all("tj" not in alt["owners"] for alt in prediction["alternatives"])


def test_a_member_the_model_has_never_seen_is_never_predicted(client):
    push_synthetic(client, "h1", 600)

    body = predict(client, "h1", ["Paper towels", "Coke Zero 12-pack"], (*PEOPLE, "newcomer"))

    for prediction in body["predictions"]:
        assert "newcomer" not in prediction["owners"]
        assert prediction["status"] == "predicted"


def test_a_request_with_only_unseen_people_is_unknown(client):
    push_synthetic(client, "h1", 600)

    body = predict(client, "h1", ["Paper towels"], ("stranger",))

    assert body["predictions"][0]["status"] == "unknown"


def test_alternatives_are_valid_other_owner_sets(client):
    push_synthetic(client, "h1", 900)

    body = predict(client, "h1", ["Whole milk gallon", "Sandwich bread", "Chicken breast"])

    for prediction in body["predictions"]:
        chosen = sorted(prediction["owners"])
        assert len(prediction["alternatives"]) <= 3
        for alt in prediction["alternatives"]:
            assert sorted(alt["owners"]) != chosen
            assert set(alt["owners"]) <= set(PEOPLE)
            assert 0 <= alt["confidence"] <= 1
        confidences = [a["confidence"] for a in prediction["alternatives"]]
        assert confidences == sorted(confidences, reverse=True)


def test_households_do_not_share_what_they_learn(client):
    rows_a = [
        {
            "observation_id": f"a{n}",
            "purchased_on": f"2026-01-{1 + n % 28:02d}",
            "description": "Shared cheese wheel" if n % 2 else f"Filler item {n}",
            "member_ids": ["x", "y"],
            "owner_ids": ["x"],
        }
        for n in range(60)
    ]
    rows_b = [{**r, "observation_id": f"b{n}", "owner_ids": ["y"]} for n, r in enumerate(rows_a)]
    client.put("/ownership/households/ha/observations", json={"observations": rows_a})
    client.put("/ownership/households/hb/observations", json={"observations": rows_b})

    a = predict(client, "ha", ["Shared cheese wheel"], ("x", "y"))["predictions"][0]
    b = predict(client, "hb", ["Shared cheese wheel"], ("x", "y"))["predictions"][0]

    assert a["owners"] == ["x"]
    assert b["owners"] == ["y"]


def test_an_empty_item_list_is_fine(client):
    push_synthetic(client, "h1", 300)

    body = predict(client, "h1", [])

    assert body["predictions"] == []
    assert body["model_version"].startswith("knn-calibrated-")


def test_history_status_shows_the_serving_model_version(client):
    push_synthetic(client, "h1", 300)
    assert client.get("/ownership/households/h1/history").json()["model_version"] is None

    version = predict(client, "h1", ["Eggs"])["model_version"]

    assert client.get("/ownership/households/h1/history").json()["model_version"] == version


def test_versions_and_metrics_are_persisted_per_household(setup):
    client, _, manager, root = setup
    push_synthetic(client, "h1", 400)
    first = predict(client, "h1", ["Eggs"])["model_version"]
    push_synthetic(client, "h1", 450, seed=8)
    second = predict(client, "h1", ["Eggs"])["model_version"]

    meta = json.loads((root / "models" / "h1" / "meta.json").read_text(encoding="utf-8"))

    assert meta["current"]["version"] == second
    assert [v["version"] for v in meta["versions"]] == [first, second]
    assert meta["current"]["history_size"] >= 400
    assert meta["current"]["people"] == list(PEOPLE)
    assert meta["current"]["metrics"]["calibrated"] is True
    assert meta["current"]["metrics"]["holdout"]["rows"] > 0
    assert manager.metadata("h1") == meta
    assert manager.metadata("never-trained") is None


def test_invalid_requests_are_rejected(client):
    bad = request("has space", ["Eggs"])

    assert client.post("/ownership/predict", json=bad).status_code == 422


def test_thirty_items_are_predicted_in_under_a_second_for_a_thousand_observations(client):
    push_synthetic(client, "big", 1000)
    items = [p.item for p in generate(60, 3)][:30]
    predict(client, "big", items[:3])

    started = time.perf_counter()
    body = predict(client, "big", items)
    elapsed = time.perf_counter() - started

    assert len(body["predictions"]) == 30
    assert elapsed < 1.0


def test_the_default_model_serves_end_to_end(tmp_path):
    store = HistoryStore(tmp_path / "history")
    manager = ModelManager(store, EMBEDDER, models_dir=tmp_path / "models")
    app.dependency_overrides[get_store] = lambda: store
    app.dependency_overrides[get_manager] = lambda: manager
    try:
        client = TestClient(app)
        push_synthetic(client, "h1", 500)

        body = predict(client, "h1", ["Coke Zero 12-pack"])

        assert body["model_version"].startswith("combined-calibrated-500-")
        assert body["predictions"][0]["owners"] == ["tj"]
    finally:
        app.dependency_overrides.clear()


def test_an_unknown_model_name_is_rejected_at_startup(tmp_path):
    with pytest.raises(ValueError, match="unknown model"):
        ModelManager(HistoryStore(), EMBEDDER, "nonsense")
