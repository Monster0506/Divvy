import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.embed import make_embedder
from divvy_ml.models.abstain import MIN_HISTORY
from divvy_ml.ownership import service
from divvy_ml.ownership.demo import (
    CATALOG,
    DEMO_ENV,
    DEMO_POLICY,
    DemoModels,
    catalog_dataset,
    demo_enabled,
)
from divvy_ml.ownership.manager import ModelManager
from divvy_ml.ownership.router import get_manager, get_store
from divvy_ml.ownership.schemas import PredictRequest
from divvy_ml.ownership.store import HistoryStore
from divvy_ml.synthetic import generate

PEOPLE = ("sumedh", "savar", "andrew", "tj")
EMBEDDER = make_embedder("hashing")
SIX = [f"p{i}" for i in range(6)]


def push_synthetic(client, household, size, seed=7):
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
    response = client.put(
        f"/ownership/households/{household}/observations", json={"observations": rows}
    )
    assert response.status_code == 200


def predict(client, household, items, people=PEOPLE):
    response = client.post(
        "/ownership/predict",
        json={
            "household_id": household,
            "people": [{"id": p, "name": p.title()} for p in people],
            "items": [{"item_id": f"i{n}", "description": d} for n, d in enumerate(items)],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def by_item(body):
    return {p["item_id"]: p for p in body["predictions"]}


def ask(state, people, items):
    request = PredictRequest(
        household_id="h",
        people=[{"id": p, "name": p} for p in people],
        items=[{"item_id": str(i), "description": d} for i, d in enumerate(items)],
    )
    return {int(p.item_id): p for p in service.predict(state, request, DEMO_POLICY).predictions}


@pytest.fixture(scope="module")
def six():
    return DemoModels(EMBEDDER).state_for(SIX)


# --- the catalog ------------------------------------------------------------------------------


def test_the_demo_history_is_big_enough_chronological_and_uses_only_the_given_people():
    data = catalog_dataset(SIX)

    assert len(data) >= MIN_HISTORY * 10
    assert data.dates == sorted(data.dates)
    assert all(set(o.owners) <= set(SIX) and o.owners for o in data.observations)


def test_the_same_people_always_give_the_same_history():
    assert catalog_dataset(SIX).records() == catalog_dataset(SIX).records()


def test_every_catalog_item_appears_at_the_end_of_the_history_too():
    data = catalog_dataset(SIX)
    names = {name for _, group in CATALOG for name in group}

    assert {o.item for o in data.observations[-len(names) :]} == names


def test_slots_wrap_for_a_household_smaller_than_the_catalog_expects():
    data = catalog_dataset(["a", "b"])

    owners = {o.item: set(o.owners) for o in data.observations}
    assert owners["coke zero"] == {"a"}
    assert owners["paneer block"] == {"b"}
    assert owners["oat milk"] == {"a"}
    assert owners["milk"] == {"a", "b"}


# --- what it answers --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("item", "owners"),
    [
        ("banana chocolate", {"p0"}),
        ("chocolate chip", {"p1"}),
        ("totes umbrella", {"p2"}),
        ("boneless chicken breast", {"p0"}),
        ("donuts", {"p0"}),
        ("paneer block", {"p1"}),
        ("great value plastic wrap", set(SIX)),
        ("bananas", set(SIX)),
        ("scrub daddy", set(SIX)),
        ("red wine", {"p0", "p1"}),
    ],
)
def test_familiar_items_are_guessed_with_high_confidence(six, item, owners):
    prediction = ask(six, SIX, [item])[0]

    assert prediction.status.value == "predicted"
    assert set(prediction.owners) == owners
    assert prediction.confidence >= 0.9


def test_a_variant_of_a_catalog_item_is_still_recognised(six):
    prediction = ask(six, SIX, ["great value donuts glazed"])[0]

    assert prediction.status.value == "predicted"
    assert prediction.confidence >= 0.5


def test_nonsense_is_still_unknown(six):
    predictions = ask(six, SIX, ["zzzz qqq", "xkcd 0000"])

    assert {p.status.value for p in predictions.values()} == {"unknown"}


def test_a_two_person_household_gets_confident_guesses_too():
    state = DemoModels(EMBEDDER).state_for(["a", "b"])

    prediction = ask(state, ["a", "b"], ["paneer block"])[0]

    assert prediction.owners == ["b"]
    assert prediction.confidence >= 0.9


def test_people_missing_from_the_request_are_left_out(six):
    prediction = ask(six, SIX[:3], ["banana chocolate", "great value water"])

    assert set(prediction[0].owners) == {"p0"}
    assert set(prediction[1].owners) == {"p0", "p1", "p2"}


def test_the_model_is_built_once_per_set_of_people():
    models = DemoModels(EMBEDDER)

    assert models.state_for(SIX) is models.state_for(SIX)
    assert models.state_for(SIX) is not models.state_for(SIX[:3])


def test_the_version_says_it_is_the_demo_model(six):
    assert six.version.startswith("demo-")
    assert six.metrics["demo"] is True


# --- the switch and the hand-over --------------------------------------------------------------


@pytest.mark.parametrize(
    ("value", "expected"), [("true", True), ("1", True), ("ON", True), ("", False), ("no", False)]
)
def test_the_switch_reads_the_environment(monkeypatch, value, expected):
    monkeypatch.setenv(DEMO_ENV, value)

    assert demo_enabled() is expected


@pytest.fixture
def served(tmp_path):
    store = HistoryStore(tmp_path / "history")
    manager = ModelManager(store, EMBEDDER, "knn-calibrated", models_dir=tmp_path / "models")
    app.dependency_overrides[get_store] = lambda: store
    app.dependency_overrides[get_manager] = lambda: manager
    app.state.demo = None
    yield TestClient(app)
    app.dependency_overrides.clear()
    app.state.demo = None


def test_with_the_switch_off_a_new_household_still_gets_unknown(served, monkeypatch):
    monkeypatch.delenv(DEMO_ENV, raising=False)

    body = predict(served, "fresh", ["banana chocolate"], PEOPLE)

    assert body["predictions"][0]["status"] == "unknown"


def test_with_the_switch_on_a_new_household_gets_confident_guesses_through_the_api(
    served, monkeypatch
):
    monkeypatch.setenv(DEMO_ENV, "true")

    body = predict(served, "fresh", ["banana chocolate", "paneer block", "bananas"], PEOPLE)

    guesses = by_item(body)
    assert body["model_version"].startswith("demo-")
    assert guesses["i0"]["owners"] == [PEOPLE[0]]
    assert guesses["i1"]["owners"] == [PEOPLE[1]]
    assert set(guesses["i2"]["owners"]) == set(PEOPLE)
    assert all(g["status"] == "predicted" and g["confidence"] >= 0.9 for g in guesses.values())


def test_the_households_own_model_takes_over_once_it_has_enough_history(served, monkeypatch):
    monkeypatch.setenv(DEMO_ENV, "true")
    push_synthetic(served, "grown", 200)

    body = predict(served, "grown", ["Coke Zero 12-pack"], PEOPLE)

    assert not body["model_version"].startswith("demo-")
    assert body["model_version"].startswith("knn-calibrated-")


def test_a_household_with_a_little_history_still_uses_the_demo_model(served, monkeypatch):
    monkeypatch.setenv(DEMO_ENV, "true")
    push_synthetic(served, "thin", 10)

    body = predict(served, "thin", ["banana chocolate"], PEOPLE)

    assert body["model_version"].startswith("demo-")
