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
    ItemConfidence,
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
from divvy_ml.models.abstain import AbstentionPolicy, unknown_stats
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
    policy: AbstentionPolicy = AbstentionPolicy()


def default_step(size: int) -> int:
    """About REFITS refits per run, but no more often than every REFIT_EVERY items."""
    return max(REFIT_EVERY, size // REFITS)


def walk_outputs(
    dataset: Dataset, model: Model, start: int, step: int
) -> tuple[np.ndarray, np.ndarray | None, np.ndarray]:
    """Probabilities, familiarity (when the model has it) and history size for dataset[start:].

    The model is refit before each block on earlier days only.
    """
    items, labels, known = dataset.items, dataset.labels(), dataset.known()
    familiarity = getattr(model, "familiarity", None)
    blocks, seen, history = [], [], []
    for fit_end, end in walk_forward(dataset.dates, start, len(dataset), step):
        model.fit(items[:fit_end], labels[:fit_end], known[:fit_end])
        blocks.append(model.predict(items[fit_end:end]))
        history.append(np.full(end - fit_end, fit_end))
        if familiarity:
            seen.append(familiarity(items[fit_end:end]))

    probs = np.vstack(blocks) if blocks else np.zeros((0, len(dataset.people)))
    return (
        probs,
        np.concatenate(seen) if familiarity and seen else None,
        np.concatenate(history) if history else np.zeros(0, dtype=int),
    )


def walk_predictions(dataset: Dataset, model: Model, start: int, step: int) -> np.ndarray:
    return walk_outputs(dataset, model, start, step)[0]


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

    policy = config.policy
    for model in models:
        probs, familiarity, history = walk_outputs(dataset, model, start, step)
        joint_fn = getattr(model, "item_confidence", None)
        abstention = {
            rule.value: _abstention(truth, probs, known, val, test, rule, config.target, joint_fn)
            for rule in Confidence
        }
        joint, correct = _confidence_and_correct(
            truth[test], probs[test], known[test], PRIMARY_RULE, joint_fn
        )
        results["models"][model.name] = {
            "validation": report(truth[val], probs[val], dataset.people, known[val], joint_fn),
            "test": report(truth[test], probs[test], dataset.people, known[test], joint_fn),
            "expanding": report(truth, probs, dataset.people, known, joint_fn),
            "test_slices": _by_slice(truth, probs, known, kinds, test, dataset.people),
            "abstention": abstention,
            "reliability": [vars(b) for b in reliability_bins(joint, correct)],
            "unknown": _unknown(truth, probs, known, history, familiarity, test, policy),
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
    truth: np.ndarray,
    probs: np.ndarray,
    known: np.ndarray,
    rule: Confidence,
    item_confidence: ItemConfidence | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    pred = decide(probs, known=known)
    if rule is Confidence.JOINT and item_confidence:
        conf = item_confidence(probs, pred, known)
    else:
        conf = confidence(probs, pred, rule, known)

    return conf, exact_correct(truth, pred, known)


def _abstention(
    truth: np.ndarray,
    probs: np.ndarray,
    known: np.ndarray,
    val: slice,
    test: slice,
    rule: Confidence,
    target: float,
    item_confidence: ItemConfidence | None = None,
) -> dict[str, Any]:
    val_conf, val_correct = _confidence_and_correct(
        truth[val], probs[val], known[val], rule, item_confidence
    )
    test_conf, test_correct = _confidence_and_correct(
        truth[test], probs[test], known[test], rule, item_confidence
    )
    threshold = pick_threshold(val_conf, val_correct, target)

    return {
        "target_accuracy": target,
        "threshold": threshold,
        "test": operating_point(test_conf, test_correct, threshold)
        if threshold is not None
        else None,
        "test_coverage": coverage_curve(test_conf, test_correct),
    }


def _unknown(
    truth: np.ndarray,
    probs: np.ndarray,
    known: np.ndarray,
    history: np.ndarray,
    familiarity: np.ndarray | None,
    test: slice,
    policy: AbstentionPolicy,
) -> dict[str, float | None] | None:
    """Share of test items the model would answer "unknown" to, and accuracy on each side."""
    if familiarity is None:
        return None

    pred = decide(probs[test], known=known[test])
    correct = exact_correct(truth[test], pred, known[test])
    return unknown_stats(correct, policy.unknown(history[test], familiarity[test]))


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
