import threading
import time

import numpy as np
import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.embed import make_embedder
from divvy_ml.ownership.config import REFIT_DELAY_ENV, refit_delay
from divvy_ml.ownership.manager import ModelManager
from divvy_ml.ownership.router import get_manager, get_store
from divvy_ml.ownership.schemas import ObservationIn
from divvy_ml.ownership.store import HistoryStore
from divvy_ml.synthetic import generate

EMBEDDER = make_embedder("hashing")
PEOPLE = ["ana", "ben"]


def obs(oid, owners=("ana",), day=1, item=None):
    return ObservationIn.model_validate(
        {
            "observation_id": oid,
            "purchased_on": f"2026-02-{day:02d}",
            "description": item or f"Item {oid}",
            "member_ids": PEOPLE,
            "owner_ids": list(owners),
        }
    )


def seeded_store(count=40):
    store = HistoryStore()
    store.upsert("h1", [obs(f"s{n}", day=1 + n % 28) for n in range(count)])
    return store


class FakeModel:
    """Records what it was fit on, and whether a fit is in progress."""

    name = "fake"
    instances: list["FakeModel"] = []

    def __init__(self, gate=None, fail=False, delay=0.0):
        self.ready = False
        self.started = threading.Event()
        self.fit_items: list[str] = []
        self._gate, self._fail, self._delay = gate, fail, delay
        FakeModel.instances.append(self)

    def fit(self, items, labels, known):
        self.ready = False
        self.started.set()
        if self._gate is not None:
            self._gate.wait(5)
        time.sleep(self._delay)
        if self._fail:
            raise RuntimeError("fit exploded")
        self.fit_items = list(items)
        self.ready = True

    def predict(self, items):
        assert self.ready, "predict called on a model that is still being fit"
        return np.full((len(items), 2), 0.5)


@pytest.fixture(autouse=True)
def reset_fakes():
    FakeModel.instances = []


def wait_for(condition, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.01)
    return False


def background_manager(store, factory, delay=0.1, **kwargs):
    manager = ModelManager(
        store, EMBEDDER, "knn", refit_delay=delay, model_factory=factory, **kwargs
    )
    manager.get("h1")
    return manager


def test_a_burst_of_twenty_corrections_causes_a_single_refit():
    store = seeded_store()
    manager = background_manager(store, FakeModel, delay=0.3)
    assert manager.builds["h1"] == 1

    for n in range(20):
        store.upsert("h1", [obs(f"s{n}", owners=("ben",), day=1 + n % 28)])
        manager.notify_write("h1")

    assert wait_for(lambda: manager.builds["h1"] == 2)
    time.sleep(0.5)
    assert manager.builds["h1"] == 2
    assert manager.get("h1").revision == store.revision("h1")
    manager.close()


def test_writes_that_land_during_a_refit_get_their_own_follow_up():
    store = seeded_store()
    gate = threading.Event()
    models = iter([FakeModel(), FakeModel(gate=gate), FakeModel()])
    manager = background_manager(store, lambda: next(models), delay=0.05)

    store.upsert("h1", [obs("s0", owners=("ben",))])
    manager.notify_write("h1")
    assert FakeModel.instances[1].started.wait(5)

    store.upsert("h1", [obs("s1", owners=("ben",))])
    manager.notify_write("h1")
    gate.set()

    assert wait_for(lambda: manager.builds["h1"] == 3)
    assert wait_for(lambda: manager.get("h1").revision == store.revision("h1"))
    manager.close()


def test_predictions_keep_using_the_current_model_while_a_refit_runs():
    store = seeded_store()
    gate = threading.Event()
    models = iter([FakeModel(), FakeModel(gate=gate)])
    manager = background_manager(store, lambda: next(models))
    before = manager.get("h1")

    store.upsert("h1", [obs("s0", owners=("ben",))])
    manager.notify_write("h1")
    assert FakeModel.instances[1].started.wait(5)

    started = time.perf_counter()
    during = manager.get("h1")
    elapsed = time.perf_counter() - started

    assert during is before
    assert elapsed < 0.05
    gate.set()
    assert wait_for(lambda: manager.get("h1").version != before.version)
    manager.close()


def test_a_prediction_never_sees_a_half_fitted_model():
    store = seeded_store()
    manager = background_manager(store, lambda: FakeModel(delay=0.02), delay=0.01)
    seen, stop = [], threading.Event()

    def reader():
        while not stop.is_set():
            state = manager.get("h1")
            seen.append(state.model.ready)
            state.model.predict(["x"])

    threads = [threading.Thread(target=reader) for _ in range(4)]
    [t.start() for t in threads]
    for n in range(15):
        store.upsert("h1", [obs(f"s{n}", owners=("ben",), day=1 + n % 28)])
        manager.notify_write("h1")
        time.sleep(0.03)

    manager.flush()
    stop.set()
    [t.join() for t in threads]

    assert len(seen) > 100
    assert all(seen)
    assert manager.builds["h1"] > 2
    manager.close()


def test_a_failed_refit_keeps_the_old_model_serving_and_recovers():
    store = seeded_store()
    models = iter([FakeModel(), FakeModel(fail=True), FakeModel()])
    manager = background_manager(store, lambda: next(models))
    before = manager.get("h1")

    store.upsert("h1", [obs("s0", owners=("ben",))])
    manager.notify_write("h1")
    assert wait_for(lambda: "h1" in manager.failures)

    assert manager.get("h1") is before
    assert "fit exploded" in manager.failures["h1"]

    manager.notify_write("h1")
    assert wait_for(lambda: manager.get("h1").version != before.version)
    assert "h1" not in manager.failures
    manager.close()


def test_without_background_refits_the_next_prediction_rebuilds():
    store = seeded_store()
    manager = ModelManager(store, EMBEDDER, "knn", model_factory=FakeModel)
    first = manager.get("h1")

    store.upsert("h1", [obs("s0", owners=("ben",))])
    manager.notify_write("h1")

    assert manager.get("h1").version != first.version
    assert manager.builds["h1"] == 2


def test_the_first_model_is_built_before_the_first_answer_even_with_background_refits():
    store = seeded_store()
    manager = ModelManager(store, EMBEDDER, "knn", refit_delay=60.0, model_factory=FakeModel)

    state = manager.get("h1")

    assert state is not None
    assert manager.builds["h1"] == 1
    manager.close()


def test_closing_cancels_refits_that_have_not_run():
    store = seeded_store()
    manager = background_manager(store, FakeModel, delay=30.0)
    store.upsert("h1", [obs("s0", owners=("ben",))])
    manager.notify_write("h1")

    manager.close()
    manager.notify_write("h1")

    assert manager.builds["h1"] == 1


def test_only_the_most_recent_history_is_used_when_capped():
    store = seeded_store(count=100)
    manager = ModelManager(store, EMBEDDER, "knn", max_history=40, model_factory=FakeModel)

    state = manager.get("h1")

    assert state.history_size == 40
    assert len(FakeModel.instances[0].fit_items) == 40
    newest = [r["item"] for r in store.records("h1")][-40:]
    assert FakeModel.instances[0].fit_items == newest


def test_a_refit_at_twelve_hundred_observations_stays_fast():
    store = HistoryStore()
    rows = [
        ObservationIn.model_validate(
            {
                "observation_id": f"o{p.seq}",
                "purchased_on": p.date.isoformat(),
                "description": p.item,
                "member_ids": ["sumedh", "savar", "andrew", "tj"],
                "owner_ids": sorted(p.owners),
            }
        )
        for p in generate(1200, 7)
    ]
    store.upsert("h1", rows)
    manager = ModelManager(store, EMBEDDER, "combined-calibrated")

    started = time.perf_counter()
    state = manager.get("h1")
    elapsed = time.perf_counter() - started

    assert state.history_size == 1200
    # about 0.4 s locally; a shared CI runner is several times slower, so only guard regressions
    assert elapsed < 8.0


@pytest.mark.parametrize(
    ("value", "expected"),
    [("sync", None), ("off", None), ("", None), ("0.5", 0.5), ("2", 2.0)],
)
def test_the_refit_delay_comes_from_the_environment(monkeypatch, value, expected):
    monkeypatch.setenv(REFIT_DELAY_ENV, value)

    assert refit_delay() == expected


def test_the_default_refit_delay_is_a_short_background_delay(monkeypatch):
    monkeypatch.delenv(REFIT_DELAY_ENV, raising=False)

    assert refit_delay() == 1.0


def test_over_the_api_a_correction_is_picked_up_by_a_background_refit():
    store = HistoryStore()
    manager = ModelManager(store, EMBEDDER, "knn-calibrated", refit_delay=0.1)
    app.dependency_overrides[get_store] = lambda: store
    app.dependency_overrides[get_manager] = lambda: manager
    try:
        client = TestClient(app)
        people = ["sumedh", "savar", "andrew", "tj"]
        rows = []
        for p in generate(400, 7):
            record = p.to_json()
            rows.append(
                {
                    "observation_id": f"o{record['seq']}",
                    "purchased_on": record["date"],
                    "description": record["item"],
                    "member_ids": people,
                    "owner_ids": [n for n, owns in record["owners"].items() if owns],
                }
            )
        client.put("/ownership/households/h1/observations", json={"observations": rows})
        body = {
            "household_id": "h1",
            "people": [{"id": p, "name": p} for p in people],
            "items": [{"item_id": "1", "description": "Coke Zero 12-pack"}],
        }
        first = client.post("/ownership/predict", json=body).json()

        flipped = [
            {**r, "owner_ids": ["sumedh"]} for r in rows if r["description"] == "Coke Zero 12-pack"
        ]
        client.put("/ownership/households/h1/observations", json={"observations": flipped})

        assert wait_for(
            lambda: (
                client.post("/ownership/predict", json=body).json()["model_version"]
                != first["model_version"]
            )
        )
        later = client.post("/ownership/predict", json=body).json()
        assert later["predictions"][0]["owners"] == ["sumedh"]
    finally:
        manager.close()
        app.dependency_overrides.clear()
