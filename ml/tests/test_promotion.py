import logging

import numpy as np

from divvy_ml.promotion import Promoter, compare

rng = np.random.default_rng(0)
TRUTH = rng.integers(0, 2, size=(200, 3))
TRUTH[TRUTH.sum(axis=1) == 0, 0] = 1
GOOD = np.where(TRUTH == 1, 0.97, 0.03)
# confidently wrong on a third of the items
WORSE = GOOD.copy()
WORSE[::3] = 1 - WORSE[::3]


def test_no_current_model_promotes():
    assert compare(TRUTH, GOOD).promote


def test_equal_model_promotes():
    assert compare(TRUTH, GOOD, GOOD).promote


def test_worse_model_is_rejected():
    decision = compare(TRUTH, WORSE, GOOD)

    assert not decision.promote
    assert any("accuracy" in r for r in decision.reasons)
    assert any("ECE" in r for r in decision.reasons)


def test_calibration_regression_alone_blocks():
    underconfident = np.where(TRUTH == 1, 0.7, 0.3)
    decision = compare(TRUTH, underconfident, GOOD, threshold=0.0)

    assert not decision.promote
    assert all("ECE" in r for r in decision.reasons)


def test_nothing_above_threshold_is_rejected():
    unsure = np.full_like(GOOD, 0.5)

    assert not compare(TRUTH, unsure, GOOD).promote


def test_promoter_keeps_previous_model_on_rejection(caplog):
    models = {"good": GOOD, "worse": WORSE}
    promoter = Promoter[str]()

    with caplog.at_level(logging.INFO, logger="divvy_ml.promotion"):
        assert promoter.consider("good", models.__getitem__, TRUTH).promote
        assert not promoter.consider("worse", models.__getitem__, TRUTH).promote

    assert promoter.current == "good"
    assert [d.promote for d in promoter.history] == [True, False]
    assert "promotion rejected" in caplog.text


def test_promoter_keeps_previous_model_on_failure():
    def predict(model: str) -> np.ndarray:
        if model == "broken":
            raise RuntimeError("boom")
        return GOOD

    promoter = Promoter[str](current="good")
    decision = promoter.consider("broken", predict, TRUTH)

    assert not decision.promote
    assert promoter.current == "good"
    assert "boom" in decision.reasons[0]
