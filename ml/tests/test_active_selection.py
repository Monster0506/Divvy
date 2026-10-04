import numpy as np

from divvy_ml.active import select_batch, uncertainty


def _synthetic(groups: int = 12, per_group: int = 5, dim: int = 32, seed: int = 0):
    rng = np.random.default_rng(seed)
    centres = rng.normal(size=(groups, dim))
    rows = [c + rng.normal(scale=0.02, size=dim) for c in centres for _ in range(per_group)]
    return np.array(rows), rng.random(groups * per_group)


def _unit(x):
    return x / np.linalg.norm(x, axis=1, keepdims=True)


def test_batch_of_ten_has_no_near_duplicates():
    embeddings, scores = _synthetic()
    batch = select_batch(embeddings, scores, size=10)
    assert len(batch) == 10
    unit = _unit(embeddings[batch])
    sims = unit @ unit.T - np.eye(10)
    assert sims.max() < 0.9


def test_batch_spans_distinct_groups():
    embeddings, scores = _synthetic()
    batch = select_batch(embeddings, scores, size=10)
    assert len({i // 5 for i in batch}) == 10


def test_prefers_uncertain_item_within_a_group():
    embeddings = np.array([[1.0, 0.0], [0.99, 0.01], [0.0, 1.0], [0.01, 0.99]])
    batch = select_batch(embeddings, [0.2, 0.9, 0.3, 0.1], size=2)
    assert sorted(batch) == [1, 2]


def test_exact_duplicates_are_collapsed_even_if_batch_is_short():
    embeddings = np.array([[1.0, 0.0]] * 4 + [[0.0, 1.0]])
    batch = select_batch(embeddings, [0.5, 0.6, 0.7, 0.8, 0.1], size=3)
    assert sorted(batch) == [3, 4]


def test_deterministic_and_handles_small_inputs():
    embeddings, scores = _synthetic()
    assert select_batch(embeddings, scores) == select_batch(embeddings, scores)
    assert select_batch(np.zeros((0, 4)), [], size=5) == []
    assert len(select_batch(embeddings[::5][:3], scores[::5][:3], size=10)) == 3


def test_uncertainty_treats_missing_prediction_as_maximal():
    assert np.allclose(uncertainty([0.9, None, 1.0]), [0.1, 1.0, 0.0])
