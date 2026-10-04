import hashlib
import threading
from datetime import date

import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.eval.dataset import Source, load
from divvy_ml.ownership.router import get_store
from divvy_ml.ownership.schemas import ObservationIn
from divvy_ml.ownership.store import HistoryStore, InvalidHouseholdId

PEOPLE = ["ana", "ben"]


def obs(oid, owners=("ana",), members=("ana", "ben"), day=1, item="Milk", **extra):
    return {
        "observation_id": oid,
        "purchased_on": f"2026-01-{day:02d}",
        "description": item,
        "member_ids": list(members),
        "owner_ids": list(owners),
        **extra,
    }


def parsed(*payloads):
    return [ObservationIn.model_validate(p) for p in payloads]


@pytest.fixture
def store():
    return HistoryStore()


@pytest.fixture
def client(store):
    app.dependency_overrides[get_store] = lambda: store
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_upsert_adds_then_updates_in_place(store):
    store.upsert("h1", parsed(obs("a"), obs("b", day=2)))

    size = store.upsert("h1", parsed(obs("a", owners=("ben",))))

    assert size == 2
    records = store.records("h1")
    assert [r["seq"] for r in records] == [1, 2]
    assert records[0]["owners"] == {"ana": False, "ben": True}


def test_upsert_is_idempotent(store):
    batch = parsed(obs("a"), obs("b", day=2))

    store.upsert("h1", batch)
    first = store.records("h1")
    store.upsert("h1", batch)

    assert store.records("h1") == first


def test_records_are_ordered_by_date_then_arrival(store):
    store.upsert("h1", parsed(obs("late", day=9), obs("early", day=1), obs("same", day=1)))

    ids = [r["meta"]["observation_id"] for r in store.records("h1")]

    assert ids == ["early", "same", "late"]


def test_replace_swaps_the_whole_history(store):
    store.upsert("h1", parsed(obs("a"), obs("b", day=2)))

    size = store.upsert("h1", parsed(obs("c", day=3)), replace=True)

    assert size == 1
    assert [r["meta"]["observation_id"] for r in store.records("h1")] == ["c"]


def test_delete_removes_one_and_reports_missing(store):
    store.upsert("h1", parsed(obs("a"), obs("b", day=2)))

    assert store.delete("h1", "a") is True
    assert store.delete("h1", "a") is False
    assert store.count("h1") == 1


def test_households_never_share_history(store):
    store.upsert("h1", parsed(obs("a")))
    store.upsert("h2", parsed(obs("z", item="Rice")))

    assert [r["item"] for r in store.records("h1")] == ["Milk"]
    assert [r["item"] for r in store.records("h2")] == ["Rice"]
    assert store.count("h3") == 0
    assert store.dataset("h3") is None


def test_dataset_carries_membership_for_late_joiners(store):
    store.upsert(
        "h1",
        parsed(
            obs("a", members=("ana", "ben"), owners=("ana",), day=1),
            obs("b", members=("ana", "ben", "cy"), owners=("cy",), day=5, item="Rice"),
        ),
    )

    dataset = store.dataset("h1")

    assert dataset.people == ("ana", "ben", "cy")
    assert dataset.known().tolist() == [[True, True, False], [True, True, True]]
    assert dataset.source is Source.REAL


@pytest.mark.parametrize("bad", ["", "a b", "../x", "a/b", "a.b", "-lead", "x" * 65])
def test_unsafe_household_ids_are_rejected(store, bad):
    with pytest.raises(InvalidHouseholdId):
        store.count(bad)


def test_history_persists_on_disk_and_loads_with_the_evaluation_loader(tmp_path):
    first = HistoryStore(tmp_path)
    first.upsert("h1", parsed(obs("a"), obs("b", day=2, item="Rice")))

    second = HistoryStore(tmp_path)
    loaded = load(tmp_path / "h1.jsonl", expect=Source.REAL)

    assert second.count("h1") == 2
    assert loaded.items == ["Milk", "Rice"]
    assert [p.name for p in tmp_path.iterdir()] == ["h1.jsonl"]


def test_concurrent_writers_lose_nothing(tmp_path):
    shared = HistoryStore(tmp_path)

    def write(n):
        shared.upsert("h1", parsed(obs(f"id{n}", day=1 + n % 28)))

    threads = [threading.Thread(target=write, args=(n,)) for n in range(25)]
    [t.start() for t in threads]
    [t.join() for t in threads]

    assert shared.count("h1") == 25
    assert len({r["seq"] for r in shared.records("h1")}) == 25


def test_put_observations_returns_counts(client):
    response = client.put(
        "/ownership/households/h1/observations",
        json={"observations": [obs("a"), obs("b", day=2)]},
    )

    assert response.status_code == 200
    assert response.json() == {"household_id": "h1", "received": 2, "history_size": 2}


def test_put_with_replace_resyncs(client):
    client.put("/ownership/households/h1/observations", json={"observations": [obs("a")]})

    response = client.put(
        "/ownership/households/h1/observations?replace=true",
        json={"observations": [obs("b", day=2)]},
    )

    assert response.json()["history_size"] == 1


def test_delete_observation_and_404(client):
    client.put("/ownership/households/h1/observations", json={"observations": [obs("a")]})

    assert client.delete("/ownership/households/h1/observations/a").status_code == 204
    assert client.delete("/ownership/households/h1/observations/a").status_code == 404


def test_history_status_reports_readiness(client):
    empty = client.get("/ownership/households/h1/history").json()
    assert empty == {
        "household_id": "h1",
        "history_size": 0,
        "min_history": 30,
        "ready": False,
        "model_version": None,
        "digest": hashlib.sha256(b"").hexdigest(),
    }

    rows = [obs(f"id{n}", day=1 + n % 28, item=f"Item {n}") for n in range(30)]
    client.put("/ownership/households/h1/observations", json={"observations": rows})

    assert client.get("/ownership/households/h1/history").json()["ready"] is True


@pytest.mark.parametrize(
    "payload",
    [
        {"observations": []},
        {"observations": [obs("a", owners=("zed",))]},
        {"observations": [obs("a"), obs("a")]},
        {"observations": [obs("a", item="   ")]},
        {"observations": [obs("a", members=("ana", "ana"))]},
        {"observations": [obs("a", owners=())]},
        {"observations": [obs("a", source="guess")]},
        {"observations": [{**obs("a"), "extra": 1}]},
        {"observations": [obs("a", day=1) | {"purchased_on": "last week"}]},
        {},
    ],
)
def test_put_rejects_invalid_bodies(client, payload):
    response = client.put("/ownership/households/h1/observations", json=payload)

    assert response.status_code == 422


@pytest.mark.parametrize("household", ["a b", "a.b", "-x", "x" * 65])
def test_endpoints_reject_unsafe_household_ids(client, household):
    assert client.get(f"/ownership/households/{household}/history").status_code == 422


def test_invalid_date_object_is_a_date():
    assert parsed(obs("a"))[0].purchased_on == date(2026, 1, 1)
