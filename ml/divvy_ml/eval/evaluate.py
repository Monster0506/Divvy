"""Walk-forward evaluation: each item is predicted from strictly earlier days.

One pass from `min_history` onward feeds three views: validation (picks the auto-assign
threshold), test (the future holdout, scored with that threshold held fixed) and expanding.
"""

from collections import Counter
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from typing import Any

import numpy as np

from divvy_ml.eval import slices
from divvy_ml.eval.coverage import (
    TARGET_ACCURACY,
    coverage_curve,
    operating_point,
    pick_threshold,
)
from divvy_ml.eval.dataset import Dataset, DatasetError
from divvy_ml.eval.metrics import (
    Confidence,
    confidence,
    decide,
    exact_correct,
    reliability_bins,
    report,
)
from divvy_ml.eval.split import (
    TRAIN_FRACTION,
    VALIDATION_FRACTION,
    aligned,
    chrono_split,
    walk_forward,
)
from divvy_ml.models.base import Model

REFIT_EVERY = 10
REFITS = 100
MIN_HISTORY = 50
PRIMARY_RULE = Confidence.JOINT

Results = dict[str, Any]


@dataclass(frozen=True)
class Config:
    step: int | None = None
    train: float = TRAIN_FRACTION
    val: float = VALIDATION_FRACTION
    target: float = TARGET_ACCURACY
    min_history: int = MIN_HISTORY


def default_step(size: int) -> int:
    """About REFITS refits per run, but no more often than every REFIT_EVERY items."""
    return max(REFIT_EVERY, size // REFITS)


def walk_predictions(dataset: Dataset, model: Model, start: int, step: int) -> np.ndarray:
    """Probabilities for dataset[start:], refitting before each block on earlier days only."""
    items, labels, known = dataset.items, dataset.labels(), dataset.known()
    blocks = []
    for fit_end, end in walk_forward(dataset.dates, start, len(dataset), step):
        model.fit(items[:fit_end], labels[:fit_end], known[:fit_end])
        blocks.append(model.predict(items[fit_end:end]))

    return np.vstack(blocks) if blocks else np.zeros((0, len(dataset.people)))


def evaluate(dataset: Dataset, models: Sequence[Model], config: Config | None = None) -> Results:
    config = config or Config()
    dates = dataset.dates
    split = chrono_split(dates, config.train, config.val)
    if not split.val or not split.validation or not split.testing:
        raise DatasetError(
            f"{len(dataset)} observations is too few to split into warm-up, validation and test"
        )

    start = aligned(dates, min(config.min_history, split.val))
    step = config.step or default_step(len(dataset))
    truth = dataset.labels()[start:]
    known = dataset.known()[start:]
    kinds = np.array(slices.assign(dataset.observations, start))
    val = slice(split.val - start, split.test - start)
    test = slice(split.test - start, None)

    results: Results = {
        "config": {**asdict(config), "step": step},
        "people": list(dataset.people),
        "dataset": _describe(dataset, split.val, split.test, start, kinds[test]),
        "models": {},
    }

    for model in models:
        probs = walk_predictions(dataset, model, start, step)
        abstention = {
            rule.value: _abstention(truth, probs, known, val, test, rule, config.target)
            for rule in Confidence
        }
        joint, correct = _confidence_and_correct(
            truth[test], probs[test], known[test], PRIMARY_RULE
        )
        results["models"][model.name] = {
            "validation": report(truth[val], probs[val], dataset.people, known[val]),
            "test": report(truth[test], probs[test], dataset.people, known[test]),
            "expanding": report(truth, probs, dataset.people, known),
            "test_slices": _by_slice(truth, probs, known, kinds, test, dataset.people),
            "abstention": abstention,
            "reliability": [vars(b) for b in reliability_bins(joint, correct)],
        }

    results["recommendation"] = _recommend(results)
    return results


def _describe(
    dataset: Dataset, val: int, test: int, start: int, test_kinds: np.ndarray
) -> dict[str, Any]:
    counts = Counter(test_kinds.tolist())
    return {
        "size": len(dataset),
        "source": dataset.source.value,
        "first_date": dataset.dates[0].isoformat(),
        "last_date": dataset.dates[-1].isoformat(),
        "warmup": val,
        "validation": test - val,
        "test": len(dataset) - test,
        "scored_from": start,
        "test_slices": {s.value: counts[s.value] for s in slices.Slice if counts[s.value]},
    }


def _by_slice(
    truth: np.ndarray,
    probs: np.ndarray,
    known: np.ndarray,
    kinds: np.ndarray,
    test: slice,
    people: Sequence[str],
) -> dict[str, Any]:
    t, p, k, kind = truth[test], probs[test], known[test], kinds[test]
    return {
        s.value: report(t[kind == s], p[kind == s], people, k[kind == s])
        for s in slices.Slice
        if (kind == s).any()
    }


def _confidence_and_correct(
    truth: np.ndarray, probs: np.ndarray, known: np.ndarray, rule: Confidence
) -> tuple[np.ndarray, np.ndarray]:
    pred = decide(probs, known=known)
    return confidence(probs, pred, rule, known), exact_correct(truth, pred, known)


def _abstention(
    truth: np.ndarray,
    probs: np.ndarray,
    known: np.ndarray,
    val: slice,
    test: slice,
    rule: Confidence,
    target: float,
) -> dict[str, Any]:
    val_conf, val_correct = _confidence_and_correct(truth[val], probs[val], known[val], rule)
    test_conf, test_correct = _confidence_and_correct(truth[test], probs[test], known[test], rule)
    threshold = pick_threshold(val_conf, val_correct, target)

    return {
        "target_accuracy": target,
        "threshold": threshold,
        "test": operating_point(test_conf, test_correct, threshold)
        if threshold is not None
        else None,
        "test_coverage": coverage_curve(test_conf, test_correct),
    }


def _recommend(results: Results) -> dict[str, Any] | None:
    """Best model by test exact match, with its validated joint-confidence threshold."""
    if not results["models"]:
        return None

    name = max(results["models"], key=lambda n: results["models"][n]["test"]["exact_match"])
    abstention = results["models"][name]["abstention"][PRIMARY_RULE.value]
    point = abstention["test"] or {}
    return {
        "model": name,
        "rule": PRIMARY_RULE.value,
        "threshold": abstention["threshold"],
        "auto_rate": point.get("auto_rate"),
        "auto_accuracy": point.get("auto_accuracy"),
        "review_rate": point.get("review_rate"),
    }
