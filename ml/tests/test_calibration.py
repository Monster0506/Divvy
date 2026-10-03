import numpy as np
import pytest

from divvy_ml.embed import make_embedder
from divvy_ml.eval.dataset import Source, from_records
from divvy_ml.eval.evaluate import Config, evaluate
from divvy_ml.eval.metrics import Confidence, confidence, decide
from divvy_ml.models import (
    CalibratedModel,
    IsotonicCalibrator,
    KNNModel,
    PlattCalibrator,
    make_models,
)
from divvy_ml.models.abstain import AbstentionPolicy, unknown_stats
from divvy_ml.synthetic import generate

EMBEDDER = make_embedder("hashing")


def synthetic(size: int, seed: int = 3):
    return from_records([p.to_json() for p in generate(size, seed)], Source.SYNTHETIC)


@pytest.mark.parametrize("calibrator", [IsotonicCalibrator, PlattCalibrator])
def test_calibrators_map_overconfidence_down_and_stay_in_range(calibrator):
    rng = np.random.default_rng(0)
    conf = rng.uniform(0.5, 1.0, 400)
    correct = (rng.uniform(size=400) < conf - 0.3).astype(int)

    fitted = calibrator()
    fitted.fit(conf, correct)
    mapped = fitted.predict(np.array([0.5, 0.75, 0.95, 1.2, -0.1]))

    assert ((mapped >= 0) & (mapped <= 1)).all()
    assert mapped[2] < 0.95
    assert mapped[0] <= mapped[1] <= mapped[2] + 1e-9


def test_isotonic_calibration_is_monotone():
    rng = np.random.default_rng(1)
    conf = rng.uniform(size=300)
    correct = (rng.uniform(size=300) < conf**2).astype(int)
    fitted = IsotonicCalibrator()
    fitted.fit(conf, correct)

    mapped = fitted.predict(np.linspace(0, 1, 50))

    assert (np.diff(mapped) >= -1e-12).all()


def test_too_little_history_leaves_the_raw_confidence():
    model = CalibratedModel(KNNModel(EMBEDDER))
    dataset = synthetic(60)

    model.fit(dataset.items, dataset.labels(), dataset.known())

    assert not model.calibrated
    probs = model.predict(dataset.items[:5])
    pred = decide(probs)
    raw = confidence(probs, pred, Confidence.JOINT)
    assert np.allclose(model.item_confidence(probs, pred, None), raw)


def test_enough_history_calibrates_and_changes_the_confidence():
    model = CalibratedModel(KNNModel(EMBEDDER))
    dataset = synthetic(400)

    model.fit(dataset.items, dataset.labels(), dataset.known())

    assert model.calibrated
    assert model.name == "knn-calibrated"
    probs = model.predict(dataset.items[-20:])
    pred = decide(probs)
    calibrated = model.item_confidence(probs, pred, None)
    assert ((calibrated >= 0) & (calibrated <= 1)).all()
    assert not np.allclose(calibrated, confidence(probs, pred, Confidence.JOINT))


def test_predictions_are_unchanged_by_calibration():
    dataset = synthetic(400)
    plain = KNNModel(EMBEDDER)
    wrapped = CalibratedModel(KNNModel(EMBEDDER))
    plain.fit(dataset.items, dataset.labels(), dataset.known())
    wrapped.fit(dataset.items, dataset.labels(), dataset.known())

    queries = ["Coke Zero 12-pack", "Paper towels", "Something never bought"]

    assert np.allclose(plain.predict(queries), wrapped.predict(queries))


def test_calibration_improves_item_ece_and_meets_the_accuracy_target():
    results = evaluate(synthetic(1500, seed=7), make_models(["knn", "knn-calibrated"]), Config())[
        "models"
    ]

    raw, calibrated = results["knn"], results["knn-calibrated"]
    assert calibrated["test"]["item_ece"] < raw["test"]["item_ece"] / 3
    assert calibrated["test"]["exact_match"] == pytest.approx(raw["test"]["exact_match"])

    point = calibrated["abstention"]["joint"]["test"]
    assert point is not None
    assert point["auto_accuracy"] >= 0.95


def test_a_household_with_no_history_is_unknown():
    policy = AbstentionPolicy()

    assert policy.unknown(0, None).tolist() == [True]
    assert policy.unknown(np.zeros(3, dtype=int), np.ones(3)).tolist() == [True, True, True]


def test_unknown_when_history_is_short_or_nothing_is_similar():
    policy = AbstentionPolicy(min_history=30, min_similarity=0.4)
    familiarity = np.array([0.95, 0.2, 0.5])

    assert policy.unknown(100, familiarity).tolist() == [False, True, False]
    assert policy.unknown(10, familiarity).tolist() == [True, True, True]


def test_similarity_can_be_switched_off():
    policy = AbstentionPolicy(min_history=5, min_similarity=None)

    assert policy.unknown(10, np.array([0.0, 0.1])).tolist() == [False, False]


def test_unknown_stats_report_both_sides():
    correct = np.array([1, 1, 0, 0, 1])
    unknown = np.array([False, False, False, True, True])

    stats = unknown_stats(correct, unknown)

    assert stats["unknown_rate"] == pytest.approx(0.4)
    assert stats["unknown_accuracy"] == pytest.approx(0.5)
    assert stats["answered_accuracy"] == pytest.approx(2 / 3)
    assert unknown_stats(correct, np.zeros(5, dtype=bool))["unknown_accuracy"] is None


def test_unknown_items_really_are_the_ones_the_model_gets_wrong():
    results = evaluate(synthetic(900, seed=2), make_models(["knn"]), Config())

    stats = results["models"]["knn"]["unknown"]

    assert stats["answered_accuracy"] > 0.8
    if stats["unknown_accuracy"] is not None:
        assert stats["unknown_accuracy"] < stats["answered_accuracy"]


def test_models_without_familiarity_have_no_unknown_section():
    results = evaluate(synthetic(300), make_models(["frequency"]), Config())

    assert results["models"]["frequency"]["unknown"] is None
