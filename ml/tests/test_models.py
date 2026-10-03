import numpy as np
import pytest

from divvy_ml.embed import HashingEmbedder, make_embedder
from divvy_ml.models import KNNModel, LogRegModel, Neighbors, StackedModel, make_models
from divvy_ml.models.heads import fit_heads, group_counts, predict_heads

EMBEDDER = make_embedder("hashing")
ITEMS = [
    "Coke Zero 12-pack",
    "Coke Zero 24-pack",
    "Paper towels",
    "Greek yogurt",
    "Coke Zero cans",
    "Bounty paper towels 6 rolls",
    "Chobani Greek yogurt",
    "Coke Zero 12-pack",
]
LABELS = np.array(
    [[0, 1], [0, 1], [1, 1], [1, 0], [0, 1], [1, 1], [1, 0], [0, 1]],
)
KNOWN = np.ones_like(LABELS, dtype=bool)


def fitted(model, items=ITEMS, labels=LABELS, known=KNOWN):
    model.fit(items, labels, known)
    return model


def all_models():
    return [KNNModel(EMBEDDER), LogRegModel(EMBEDDER), StackedModel(EMBEDDER)]


@pytest.mark.parametrize("model", all_models(), ids=lambda m: m.name)
def test_probabilities_are_valid_and_one_row_per_item(model):
    probs = fitted(model).predict(["Coke Zero 6-pack", "Rice", "Eggs"])

    assert probs.shape == (3, 2)
    assert ((probs >= 0) & (probs <= 1)).all()


@pytest.mark.parametrize("model", all_models(), ids=lambda m: m.name)
def test_a_reworded_item_follows_its_neighbours(model):
    probs = fitted(model).predict(["Coke Zero 6-pack", "Bounty paper towels", "Greek yogurt tub"])

    soda, towels, yogurt = probs
    assert soda[1] > soda[0]
    assert towels[0] > 0.5 and towels[1] > 0.5
    assert yogurt[0] > yogurt[1]


@pytest.mark.parametrize("model", all_models(), ids=lambda m: m.name)
def test_an_empty_history_gives_the_uninformed_prior(model):
    model.fit([], np.zeros((0, 3)), np.zeros((0, 3), dtype=bool))

    assert np.allclose(model.predict(["Anything"]), 0.5)


@pytest.mark.parametrize("model", all_models(), ids=lambda m: m.name)
def test_predict_with_no_items_is_an_empty_matrix(model):
    assert fitted(model).predict([]).shape == (0, 2)


def test_a_held_out_row_never_votes_for_itself():
    neighbors = Neighbors(HashingEmbedder())
    neighbors.fit(["aaaa bbbb", "zzzz qqqq"], np.array([[1, 0], [0, 1]]), np.ones((2, 2), bool))

    votes, best, _ = neighbors.held_out()

    # the only other item is unrelated, so each row falls back to the 0.5 prior
    assert np.allclose(votes, 0.5, atol=0.05)
    assert (best < 0.2).all()


def test_a_member_who_was_never_there_stays_uninformed():
    labels = np.column_stack([LABELS, np.zeros(len(LABELS), dtype=int)])
    known = np.column_stack([KNOWN, np.zeros(len(LABELS), dtype=bool)])

    for model in all_models():
        probs = fitted(model, labels=labels, known=known).predict(["Coke Zero 6-pack"])
        assert probs[0, 2] == pytest.approx(0.5), model.name


def test_adding_a_member_does_not_change_predictions_for_existing_members():
    labels3 = np.column_stack([LABELS, np.zeros(len(LABELS), dtype=int)])
    known3 = np.column_stack([KNOWN, np.zeros(len(LABELS), dtype=bool)])
    queries = ["Coke Zero 6-pack", "Bounty paper towels", "Rice"]

    for two, three in zip(all_models(), all_models(), strict=True):
        before = fitted(two).predict(queries)
        after = fitted(three, labels=labels3, known=known3).predict(queries)
        assert np.allclose(before, after[:, :2], atol=1e-6), two.name


def test_unknown_history_does_not_count_as_a_no():
    # person 1 only joined for the last four rows, and owns all of them
    labels = np.array([[1, 0]] * 4 + [[1, 1]] * 4)
    known = np.array([[True, False]] * 4 + [[True, True]] * 4)
    items = ["Milk"] * 8

    neighbors = Neighbors(HashingEmbedder())
    neighbors.fit(items, labels, known)
    votes, _ = neighbors.query(["Milk"])

    assert votes[0, 1] > 0.7


def test_models_for_different_households_share_no_state():
    household_a = (ITEMS, LABELS, KNOWN)
    household_b = (
        ["Coke Zero 12-pack", "Paper towels", "Greek yogurt"],
        np.array([[1, 0], [0, 1], [1, 1]]),
        np.ones((3, 2), dtype=bool),
    )
    query = ["Coke Zero 6-pack", "Greek yogurt tub"]

    for build in (KNNModel, LogRegModel, StackedModel):
        alone = fitted(build(EMBEDDER), *household_a).predict(query)

        model_a = fitted(build(EMBEDDER), *household_a)
        fitted(build(EMBEDDER), *household_b)
        fitted(build(EMBEDDER), *household_b)

        assert np.allclose(model_a.predict(query), alone), build.__name__


def test_refitting_replaces_everything_learned_before():
    model = StackedModel(EMBEDDER)
    fitted(model)
    model.fit(["Rice"], np.array([[1, 0]]), np.ones((1, 2), dtype=bool))
    fresh = StackedModel(EMBEDDER)
    fresh.fit(["Rice"], np.array([[1, 0]]), np.ones((1, 2), dtype=bool))

    assert np.allclose(model.predict(["Coke Zero"]), fresh.predict(["Coke Zero"]))


def test_stacked_features_are_embedding_logits_and_best_similarity():
    model = fitted(StackedModel(EMBEDDER))
    votes = np.array([[0.9, 0.1]])

    features = model.features(["Eggs"], votes, np.array([0.4]))

    assert features.shape == (1, 4096 + 2 + 1)
    assert features[0, -1] == pytest.approx(0.4)
    assert features[0, 4096] > 0 > features[0, 4097]


def test_group_counts_ignore_unknown_cells():
    group = np.array([0, 0, 1])
    labels = np.array([[1, 0], [1, 1], [0, 1]])
    known = np.array([[True, False], [True, True], [True, True]])

    first, owned, missed = group_counts(group, labels, known)

    assert first.tolist() == [0, 2]
    assert owned.tolist() == [[2, 1], [0, 1]]
    assert missed.tolist() == [[0, 0], [1, 0]]


def test_heads_with_nothing_to_separate_return_the_prior():
    x = np.eye(3)
    owned = np.array([[2.0, 0.0], [1.0, 0.0], [3.0, 0.0]])
    missed = np.array([[0.0, 1.0], [0.0, 2.0], [0.0, 1.0]])

    heads = fit_heads(x, owned, missed, strength=4.0)

    assert all(isinstance(h, float) for h in heads)
    assert heads[0] > 0.8 > 0.2 > heads[1]
    assert predict_heads(heads, x).shape == (3, 2)


def test_registry_builds_every_model_around_one_embedder():
    models = make_models(embedder=EMBEDDER)

    assert [m.name for m in models] == [
        "frequency",
        "exact-item",
        "knn",
        "logreg",
        "combined",
    ]
    for model in models:
        fitted(model)
        assert model.predict(["Coke Zero"]).shape == (1, 2)
