import hashlib

import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.ownership.router import get_store
from divvy_ml.ownership.schemas import ObservationIn
from divvy_ml.ownership.store import HistoryStore, history_digest

# The backend pins the same two values in its tests, so a change to the format breaks both sides.
FIXTURE_DIGEST = "876425c0cd0676cb37dabf243eda0efea760b19a67021a50b4c23957610b992f"
EMPTY_DIGEST = hashlib.sha256(b"").hexdigest()


def obs(oid, day, description, members, owners, **extra):
    return ObservationIn.model_validate(
        {
            "observation_id": oid,
            "purchased_on": day,
            "description": description,
            "member_ids": members,
            "owner_ids": owners,
            **extra,
        }
    )


def fixture():
    return [
        obs("obs-2", "2026-10-01", "Paneer block", ["p1", "p2", "p3"], ["p1", "p3"]),
        obs("obs-1", "2026-09-30", "Coke Zero 12-pack", ["p1", "p2"], ["p2"]),
    ]


@pytest.fixture
def store():
    return HistoryStore()


@pytest.fixture
def client(store):
    app.dependency_overrides[get_store] = lambda: store
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_an_empty_history_has_the_hash_of_nothing(store):
    assert store.digest("h1") == EMPTY_DIGEST == history_digest([])


def test_the_digest_format_is_pinned_for_the_backend(store):
    store.upsert("h1", fixture())

    assert store.digest("h1") == FIXTURE_DIGEST


def test_the_digest_follows_the_documented_text_format(store):
    store.upsert("h1", fixture())
    text = (
        "obs-1|2026-09-30|Coke Zero 12-pack|p2|p1,p2\nobs-2|2026-10-01|Paneer block|p1,p3|p1,p2,p3"
    )

    assert store.digest("h1") == hashlib.sha256(text.encode()).hexdigest()


def test_the_digest_ignores_arrival_order(store):
    first, second = fixture()
    store.upsert("a", [first, second])
    store.upsert("b", [second])
    store.upsert("b", [first])

    assert store.digest("a") == store.digest("b")


def test_the_digest_ignores_the_order_of_ids_inside_an_observation(store):
    store.upsert("a", [obs("o", "2026-09-30", "Eggs", ["p1", "p2"], ["p1", "p2"])])
    store.upsert("b", [obs("o", "2026-09-30", "Eggs", ["p2", "p1"], ["p2", "p1"])])

    assert store.digest("a") == store.digest("b")


@pytest.mark.parametrize(
    "changed",
    [
        obs("obs-1", "2026-09-30", "Coke Zero 12-pack", ["p1", "p2"], ["p1"]),
        obs("obs-1", "2026-09-30", "Coke Zero 12-pack", ["p1", "p2", "p9"], ["p2"]),
        obs("obs-1", "2026-09-29", "Coke Zero 12-pack", ["p1", "p2"], ["p2"]),
        obs("obs-1", "2026-09-30", "Coke Zero 24-pack", ["p1", "p2"], ["p2"]),
    ],
    ids=["owner", "member", "date", "description"],
)
def test_a_changed_label_changes_the_digest(store, changed):
    store.upsert("h1", fixture())
    before = store.digest("h1")

    store.upsert("h1", [changed])

    assert store.digest("h1") != before


def test_how_a_label_was_made_does_not_change_the_digest(store):
    store.upsert("a", fixture())
    store.upsert("b", fixture())
    store.upsert(
        "b",
        [
            obs(
                "obs-1",
                "2026-09-30",
                "Coke Zero 12-pack",
                ["p1", "p2"],
                ["p2"],
                source="predicted_corrected",
            )
        ],
    )

    assert store.digest("a") == store.digest("b")


def test_a_deleted_label_changes_the_digest(store):
    store.upsert("h1", fixture())
    before = store.digest("h1")

    store.delete("h1", "obs-1")

    assert store.digest("h1") != before


def test_the_history_status_carries_the_digest(client, store):
    empty = client.get("/ownership/households/h1/history").json()
    assert empty["digest"] == EMPTY_DIGEST

    store.upsert("h1", fixture())

    assert client.get("/ownership/households/h1/history").json()["digest"] == FIXTURE_DIGEST


def test_replacing_with_an_empty_history_clears_the_household(client, store):
    store.upsert("h1", fixture())

    response = client.put(
        "/ownership/households/h1/observations?replace=true", json={"observations": []}
    )

    assert response.status_code == 200
    assert response.json() == {"household_id": "h1", "received": 0, "history_size": 0}
    assert client.get("/ownership/households/h1/history").json()["digest"] == EMPTY_DIGEST


def test_an_empty_upsert_without_replace_is_still_rejected(client, store):
    store.upsert("h1", fixture())

    response = client.put("/ownership/households/h1/observations", json={"observations": []})

    assert response.status_code == 422
    assert store.count("h1") == 2
