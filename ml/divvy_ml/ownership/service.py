"""Turn a household model's probabilities into the API's predictions."""

from itertools import combinations

import numpy as np

from divvy_ml.eval.metrics import Confidence, confidence, decide
from divvy_ml.models.abstain import AbstentionPolicy
from divvy_ml.ownership.manager import ModelState
from divvy_ml.ownership.schemas import (
    Alternative,
    ItemPrediction,
    PredictionStatus,
    PredictRequest,
    PredictResponse,
)

MAX_ALTERNATIVES = 3
MIN_ALTERNATIVE = 0.02
EXHAUSTIVE_LIMIT = 10


def unknown_response(request: PredictRequest, version: str) -> PredictResponse:
    return PredictResponse(
        model_version=version,
        predictions=[
            ItemPrediction(item_id=item.item_id, status=PredictionStatus.UNKNOWN, confidence=0)
            for item in request.items
        ],
    )


def predict(
    state: ModelState, request: PredictRequest, policy: AbstentionPolicy
) -> PredictResponse:
    """Predict owners for the request's items among the request's people.

    People the model has never seen cannot be predicted, and people not in the request (for example
    archived ones) are left out entirely.
    """
    if not request.items:
        return PredictResponse(model_version=state.version, predictions=[])

    ids = [p.id for p in request.people]
    column = {pid: i for i, pid in enumerate(state.people)}
    descriptions = [item.description for item in request.items]

    raw = state.model.predict(descriptions)
    probs = np.zeros((len(descriptions), len(ids)))
    known = np.zeros(probs.shape, dtype=bool)
    for j, pid in enumerate(ids):
        if pid in column:
            probs[:, j] = raw[:, column[pid]]
            known[:, j] = True

    if not known.any():
        return unknown_response(request, state.version)

    familiarity_of = getattr(state.model, "familiarity", None)
    familiarity = familiarity_of(descriptions) if familiarity_of else None
    unknown = policy.unknown(state.history_size, familiarity)

    pred = decide(probs, known=known)
    item_confidence = getattr(state.model, "item_confidence", None)
    conf = (
        item_confidence(probs, pred, known)
        if item_confidence
        else confidence(probs, pred, Confidence.JOINT, known)
    )

    predictions = []
    for i, item in enumerate(request.items):
        if unknown[i]:
            predictions.append(
                ItemPrediction(item_id=item.item_id, status=PredictionStatus.UNKNOWN, confidence=0)
            )
            continue

        owners = [ids[j] for j in range(len(ids)) if pred[i, j]]
        predictions.append(
            ItemPrediction(
                item_id=item.item_id,
                status=PredictionStatus.PREDICTED,
                owners=owners,
                confidence=float(np.clip(conf[i], 0.0, 1.0)),
                alternatives=alternatives(probs[i], known[i], pred[i], ids),
            )
        )

    return PredictResponse(model_version=state.version, predictions=predictions)


def alternatives(
    probs: np.ndarray, known: np.ndarray, pred: np.ndarray, ids: list[str]
) -> list[Alternative]:
    """The most likely other owner sets, from the model's per-person probabilities.

    Probabilities are the raw (uncalibrated) chance of each set, given at least one person owns it.
    """
    people = [j for j in range(len(ids)) if known[j]]
    if len(people) > EXHAUSTIVE_LIMIT:
        candidates = _single_flips(pred, people)
    else:
        candidates = [
            frozenset(c) for r in range(1, len(people) + 1) for c in combinations(people, r)
        ]

    chosen = frozenset(j for j in people if pred[j])
    nonempty = 1 - np.prod([1 - probs[j] for j in people])
    scored = sorted(
        (
            (_set_probability(probs, people, c) / max(nonempty, 1e-9), c)
            for c in candidates
            if c != chosen
        ),
        key=lambda pair: (-pair[0], sorted(pair[1])),
    )
    return [
        Alternative(owners=[ids[j] for j in sorted(c)], confidence=float(min(p, 1.0)))
        for p, c in scored[:MAX_ALTERNATIVES]
        if p >= MIN_ALTERNATIVE
    ]


def _set_probability(probs: np.ndarray, people: list[int], owners: frozenset[int]) -> float:
    return float(np.prod([probs[j] if j in owners else 1 - probs[j] for j in people]))


def _single_flips(pred: np.ndarray, people: list[int]) -> list[frozenset[int]]:
    chosen = {j for j in people if pred[j]}
    flips = [chosen ^ {j} for j in people]
    return [frozenset(f) for f in flips if f]
