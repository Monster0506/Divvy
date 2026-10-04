import datetime as dt
import json

import numpy as np
import pytest

from divvy_ml.eval import confidence, decide, report
from divvy_ml.eval.dataset import (
    Dataset,
    DatasetError,
    Observation,
    Source,
    from_observations,
    parse_lines,
    save,
)
from divvy_ml.eval.metrics import Confidence
from divvy_ml.models import ownership_prior


def line(seq, date, item, owners):
    return json.dumps({"seq": seq, "date": date, "item": item, "owners": owners, "source": "real"})


def household_with_a_late_member():
    return parse_lines(
        [
            line(1, "2026-01-01", "Milk", {"ana": True, "ben": False}),
            line(2, "2026-01-02", "Eggs", {"ana": False, "ben": True}),
            line(3, "2026-02-01", "Rice", {"ana": True, "ben": True, "cy": False}),
            line(4, "2026-02-02", "Soda", {"ana": False, "ben": False, "cy": True}),
        ]
    )


def test_people_are_the_union_in_order_of_first_appearance():
    dataset = household_with_a_late_member()

    assert dataset.people == ("ana", "ben", "cy")


def test_a_late_member_is_unknown_before_joining():
    dataset = household_with_a_late_member()

    assert dataset.known().tolist() == [
        [True, True, False],
        [True, True, False],
        [True, True, True],
        [True, True, True],
    ]
    assert dataset.labels().tolist() == [[1, 0, 0], [0, 1, 0], [1, 1, 0], [0, 0, 1]]


def test_a_member_who_left_is_unknown_afterwards():
    dataset = parse_lines(
        [
            line(1, "2026-01-01", "Milk", {"ana": True, "ben": False}),
            line(2, "2026-02-01", "Eggs", {"ana": True}),
        ]
    )

    assert dataset.known().tolist() == [[True, True], [True, False]]


def test_membership_round_trips_through_a_file(tmp_path):
    dataset = household_with_a_late_member()
    path = tmp_path / "obs.jsonl"

    save(path, dataset)
    first = json.loads(path.read_text(encoding="utf-8").splitlines()[0])

    assert set(first["owners"]) == {"ana", "ben"}
    assert parse_lines(path.read_text(encoding="utf-8").splitlines()) == dataset


def test_an_owner_must_still_be_someone_in_the_household():
    with pytest.raises(DatasetError, match="at least one person"):
        parse_lines([line(1, "2026-01-01", "Milk", {"ana": False, "ben": False})])


def test_observations_without_members_mean_everyone():
    obs = Observation(1, dt.date(2026, 1, 1), "Milk", frozenset({"ana"}), Source.REAL)
    dataset = from_observations([obs], ("ana", "ben"), Source.REAL)

    assert dataset.known().tolist() == [[True, True]]


def test_dataset_known_defaults_to_all_for_full_households():
    dataset = Dataset(("a", "b"), (), Source.REAL)

    assert dataset.known().shape == (0, 2)


def test_prior_ignores_unknown_cells():
    labels = np.array([[1, 0], [1, 0], [0, 0], [1, 1]])
    known = np.array([[1, 0], [1, 0], [1, 0], [1, 1]], dtype=bool)

    prior = ownership_prior(labels, known)

    assert prior[0] == pytest.approx((3 + 1) / (4 + 2))
    assert prior[1] == pytest.approx((1 + 1) / (1 + 2))


def test_a_new_member_starts_uninformed_not_ruled_out():
    labels = np.zeros((10, 2), dtype=int)
    labels[:, 0] = 1
    known = np.column_stack([np.ones(10, bool), np.zeros(10, bool)])

    prior = ownership_prior(labels, known)

    assert prior[1] == pytest.approx(0.5)
    assert prior[0] > 0.9


def test_a_long_standing_member_who_never_owns_gets_a_low_prior():
    labels = np.zeros((10, 1), dtype=int)

    assert ownership_prior(labels, np.ones((10, 1), bool))[0] == pytest.approx(1 / 12)


def test_decide_never_predicts_someone_outside_the_household():
    probs = np.array([[0.9, 0.2, 0.99]])
    known = np.array([[True, True, False]])

    assert decide(probs, known=known).tolist() == [[1, 0, 0]]


def test_decide_falls_back_to_the_best_known_person():
    probs = np.array([[0.1, 0.3, 0.9]])
    known = np.array([[True, True, False]])

    assert decide(probs, known=known).tolist() == [[0, 1, 0]]


def test_unknown_cells_do_not_change_confidence():
    probs = np.array([[0.9, 0.8, 0.5]])
    pred = np.array([[1, 1, 0]])
    known = np.array([[True, True, False]])

    for rule in Confidence:
        masked = confidence(probs, pred, rule, known)[0]
        alone = confidence(probs[:, :2], pred[:, :2], rule)[0]
        assert masked == pytest.approx(alone)


def test_report_scores_only_known_cells():
    truth = np.array([[1, 0, 0], [0, 1, 0]])
    probs = np.array([[0.9, 0.1, 0.9], [0.1, 0.9, 0.9]])
    known = np.array([[True, True, False], [True, True, False]])

    masked = report(truth, probs, ("a", "b", "c"), known)
    alone = report(truth[:, :2], probs[:, :2], ("a", "b"))

    assert masked["exact_match"] == alone["exact_match"] == 1.0
    assert masked["hamming_loss"] == alone["hamming_loss"] == 0.0
    assert masked["brier"] == pytest.approx(alone["brier"])
    assert masked["ece"] == pytest.approx(alone["ece"])
    assert masked["per_person"]["c"]["support"] == 0


def test_report_rejects_a_mask_of_the_wrong_shape():
    with pytest.raises(ValueError):
        report(np.zeros((2, 2)), np.zeros((2, 2)), ("a", "b"), np.ones((2, 3), bool))


def test_adding_a_member_does_not_change_scores_for_existing_members():
    truth = np.array([[1, 0], [0, 1], [1, 1]])
    probs = np.array([[0.8, 0.2], [0.3, 0.7], [0.9, 0.6]])
    before = report(truth, probs, ("a", "b"))

    truth3 = np.column_stack([truth, np.zeros(3, int)])
    probs3 = np.column_stack([probs, np.full(3, 0.5)])
    known3 = np.column_stack([np.ones((3, 2), bool), np.zeros(3, bool)])
    after = report(truth3, probs3, ("a", "b", "c"), known3)

    for key in ("exact_match", "hamming_loss", "micro_f1", "brier", "log_loss", "ece"):
        assert after[key] == pytest.approx(before[key])
