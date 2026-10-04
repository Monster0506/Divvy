from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.embed import make_embedder
from divvy_ml.ownership.manager import ModelManager
from divvy_ml.ownership.retrain import Retrainer
from divvy_ml.ownership.schemas import ObservationIn
from divvy_ml.ownership.store import HistoryStore

EMBEDDER = make_embedder("hashing")
PEOPLE = ["ana", "ben"]


def obs(oid, day=1):
    return ObservationIn.model_validate(
        {
            "observation_id": oid,
            "purchased_on": f"2026-02-{day:02d}",
            "description": f"Item {oid}",
            "member_ids": PEOPLE,
            "owner_ids": ["ana"],
        }
    )


def add(store, count, start=0, household="h1"):
    store.upsert(household, [obs(f"o{n}", day=1 + n % 28) for n in range(start, start + count)])


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


def setup(after=10, interval=100.0):
    store = HistoryStore()
    manager = ModelManager(store, EMBEDDER, "knn")
    clock = Clock()
    return store, manager, clock, Retrainer(manager, interval, after, clock)


def test_a_household_is_trained_on_the_first_pass_and_logged():
    store, manager, _, retrainer = setup()
    add(store, 40)

    runs = retrainer.run_once()

    assert [(r.household_id, r.reason, r.history_size) for r in runs] == [("h1", "first", 40)]
    assert runs[0].version and runs[0].error is None and runs[0].metrics is not None
    assert manager.builds["h1"] == 1
    assert retrainer.runs() == runs


def test_no_run_without_new_observations_even_after_the_interval():
    store, _, clock, retrainer = setup()
    add(store, 40)
    retrainer.run_once()

    clock.now = 1000
    assert retrainer.run_once() == []


def test_enough_new_observations_trigger_a_retrain_before_the_interval():
    store, manager, _, retrainer = setup(after=10)
    add(store, 40)
    retrainer.run_once()

    add(store, 9, start=40)
    assert retrainer.run_once() == []

    add(store, 1, start=49)
    runs = retrainer.run_once()
    assert [(r.reason, r.new_observations, r.history_size) for r in runs] == [
        ("new-observations", 10, 50)
    ]
    assert manager.builds["h1"] == 2


def test_a_few_new_observations_wait_for_the_interval():
    store, _, clock, retrainer = setup(after=10, interval=100)
    add(store, 40)
    retrainer.run_once()

    add(store, 2, start=40)
    clock.now = 99
    assert retrainer.run_once() == []

    clock.now = 100
    assert [r.reason for r in retrainer.run_once()] == ["interval"]


def test_a_failed_run_is_recorded_and_retried_next_pass():
    store, manager, _, retrainer = setup()
    add(store, 40)

    def boom(_):
        raise RuntimeError("fit exploded")

    real = manager.retrain
    manager.retrain = boom
    runs = retrainer.run_once()
    assert runs[0].error and "fit exploded" in runs[0].error

    manager.retrain = real
    assert [r.error for r in retrainer.run_once()] == [None]


def test_retraining_runs_endpoint_lists_newest_first():
    store, _, _, retrainer = setup()
    add(store, 40)
    add(store, 40, household="h2")
    retrainer.run_once()

    app.state.retrainer = retrainer
    try:
        body = TestClient(app).get("/ownership/retraining").json()
    finally:
        app.state.retrainer = None

    assert [r["household_id"] for r in body] == ["h2", "h1"]
    assert body[0]["reason"] == "first"
