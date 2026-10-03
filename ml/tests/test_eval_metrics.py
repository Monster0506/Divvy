import xml.etree.ElementTree as ET

import numpy as np
import pytest

from divvy_ml.eval import (
    Confidence,
    confidence,
    coverage_curve,
    decide,
    exact_correct,
    expected_calibration_error,
    operating_point,
    pick_threshold,
    reliability_bins,
    reliability_svg,
    report,
)

PEOPLE = ("sumedh", "savar", "andrew", "tj")
TRUTH = np.array(
    [
        [0, 0, 0, 1],
        [1, 1, 1, 1],
        [1, 0, 1, 1],
        [0, 1, 0, 0],
    ]
)
PROBS = np.array(
    [
        [0.1, 0.1, 0.2, 0.9],  # exact
        [0.9, 0.9, 0.9, 0.4],  # misses tj
        [0.8, 0.1, 0.7, 0.8],  # exact
        [0.1, 0.2, 0.6, 0.1],  # predicts andrew instead of savar
    ]
)


def test_decide_never_returns_empty_set():
    pred = decide(np.array([[0.1, 0.3, 0.2, 0.05]]))

    assert pred.tolist() == [[0, 1, 0, 0]]


def test_decide_handles_a_household_of_any_size():
    pred = decide(np.array([[0.9, 0.2, 0.7], [0.1, 0.1, 0.1]]))

    assert pred.tolist() == [[1, 0, 1], [1, 0, 0]]


def test_report_matches_hand_computation():
    result = report(TRUTH, PROBS, PEOPLE)

    assert result["n"] == 4
    assert result["exact_match"] == pytest.approx(2 / 4)
    assert result["hamming_loss"] == pytest.approx(3 / 16)

    tj = result["per_person"]["tj"]
    assert (tj["precision"], tj["recall"]) == pytest.approx((1.0, 2 / 3))
    assert tj["f1"] == pytest.approx(0.8)
    assert tj["support"] == 3

    savar = result["per_person"]["savar"]
    assert (savar["precision"], savar["recall"]) == pytest.approx((1.0, 0.5))

    andrew = result["per_person"]["andrew"]
    assert (andrew["precision"], andrew["recall"]) == pytest.approx((2 / 3, 1.0))

    # tp=7 fp=1 fn=2 overall
    assert result["micro_f1"] == pytest.approx(2 * 7 / (2 * 7 + 1 + 2))
    assert result["macro_f1"] == pytest.approx(np.mean([1.0, 2 / 3, 0.8, 0.8]))


def test_report_calibration_matches_hand_computation():
    result = report(TRUTH, PROBS, PEOPLE)

    assert result["ece"] == pytest.approx(expected_calibration_error(PROBS.ravel(), TRUTH.ravel()))
    # joint confidences 0.624, 0.438, 0.408, 0.525 against hits 1, 0, 1, 0
    assert result["item_ece"] == pytest.approx(0.264, abs=1e-3)


def test_report_rejects_mismatched_shapes():
    with pytest.raises(ValueError):
        report(TRUTH, PROBS[:, :3], PEOPLE)
    with pytest.raises(ValueError):
        report(TRUTH, PROBS, PEOPLE[:3])


def test_report_on_no_items_is_all_zeros():
    result = report(np.zeros((0, 4)), np.zeros((0, 4)), PEOPLE)

    assert result["n"] == 0
    assert result["exact_match"] == 0.0
    assert result["ece"] == 0.0


def test_exact_correct_requires_the_whole_set_to_match():
    pred = decide(PROBS)

    assert exact_correct(TRUTH, pred).tolist() == [1, 0, 1, 0]


def test_confident_single_owner_scores_higher_than_uncertain():
    probs = np.array([[0.04, 0.01, 0.03, 0.94], [0.46, 0.38, 0.43, 0.51]])
    pred = decide(probs)

    for rule in Confidence:
        confident, uncertain = confidence(probs, pred, rule)
        assert confident > 0.85
        assert uncertain < 0.6


def test_joint_confidence_is_probability_of_predicted_set():
    probs = np.array([[0.2, 0.5, 0.5, 0.5]])
    pred = np.array([[0, 1, 1, 1]])

    expected = (0.8 * 0.125) / (1 - 0.8 * 0.125)
    assert confidence(probs, pred, Confidence.JOINT)[0] == pytest.approx(expected)


def test_weakest_confidence_is_the_least_certain_person():
    probs = np.array([[0.2, 0.9, 0.6, 0.1]])
    pred = np.array([[0, 1, 1, 0]])

    assert confidence(probs, pred, Confidence.WEAKEST)[0] == pytest.approx(0.6)


def test_ece_is_zero_when_confidence_matches_hit_rate():
    conf = np.array([0.5, 0.5, 0.5, 0.5])
    outcome = np.array([1, 0, 1, 0])

    assert expected_calibration_error(conf, outcome) == pytest.approx(0.0)


def test_ece_of_an_overconfident_model_is_the_gap():
    conf = np.full(10, 0.9)
    outcome = np.array([1] * 5 + [0] * 5)

    assert expected_calibration_error(conf, outcome) == pytest.approx(0.4)


def test_ece_weights_each_bin_by_its_share_of_items():
    conf = np.array([0.1, 0.1, 0.9, 0.9])
    outcome = np.array([0, 0, 1, 0])

    # half the items sit in a bin off by 0.1, half in a bin off by 0.4
    assert expected_calibration_error(conf, outcome) == pytest.approx(0.5 * 0.1 + 0.5 * 0.4)


def test_ece_of_nothing_is_zero():
    assert expected_calibration_error(np.array([]), np.array([])) == 0.0


def test_reliability_bins_count_items_and_skip_empty_bins():
    conf = np.array([0.05, 0.05, 0.95, 1.0])
    outcome = np.array([0, 1, 1, 1])

    bins = reliability_bins(conf, outcome, n_bins=10)

    assert [(b.lower, b.count) for b in bins] == [(0.0, 2), (0.9, 2)]
    assert bins[0].observed_rate == pytest.approx(0.5)
    assert bins[1].mean_confidence == pytest.approx(0.975)
    assert bins[1].upper == pytest.approx(1.0)


def test_reliability_bins_reject_bad_input():
    with pytest.raises(ValueError):
        reliability_bins(np.array([0.5]), np.array([1, 0]))
    with pytest.raises(ValueError):
        reliability_bins(np.array([0.5]), np.array([1]), n_bins=0)


def test_reliability_svg_is_well_formed_with_one_bar_per_bin():
    bins = reliability_bins(np.array([0.1, 0.5, 0.9]), np.array([0, 1, 1]))

    svg = reliability_svg(bins, title="A & B <x>")

    root = ET.fromstring(svg)
    ns = "{http://www.w3.org/2000/svg}"
    assert root.tag == f"{ns}svg"
    assert len(root.findall(f"{ns}rect")) == 1 + len(bins)
    assert "A &amp; B &lt;x&gt;" in svg


def test_operating_point_splits_auto_and_review():
    conf = np.array([0.99, 0.9, 0.7, 0.4])
    correct = np.array([1, 1, 0, 0])

    point = operating_point(conf, correct, 0.8)

    assert point["auto_rate"] == 0.5
    assert point["auto_accuracy"] == 1.0
    assert point["review_rate"] == 0.5


def test_operating_point_with_nothing_above_threshold():
    point = operating_point(np.array([0.2, 0.3]), np.array([1, 0]), 0.9)

    assert point["n_auto"] == 0
    assert point["auto_accuracy"] is None
    assert point["review_rate"] == 1.0


def test_pick_threshold_is_lowest_meeting_target():
    conf = np.linspace(0.5, 1.0, 20)
    correct = (conf >= 0.75).astype(int)

    threshold = pick_threshold(conf, correct, target=1.0, min_auto=3)

    assert threshold == pytest.approx(conf[conf >= 0.75].min())


def test_pick_threshold_gives_up_when_target_unreachable():
    conf = np.linspace(0.5, 1.0, 20)

    assert pick_threshold(conf, np.zeros(20), target=0.9, min_auto=3) is None


def test_coverage_curve_takes_most_confident_items_first():
    conf = np.array([0.2, 0.9, 0.5, 0.8])
    correct = np.array([0, 1, 0, 1])

    half, full = coverage_curve(conf, correct, rates=(0.5, 1.0))

    assert (half["auto_accuracy"], half["threshold"]) == (1.0, 0.8)
    assert full["auto_accuracy"] == 0.5
