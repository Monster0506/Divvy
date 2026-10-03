import datetime as dt

import pytest

from divvy_ml.eval.dataset import Source, from_records
from divvy_ml.eval.split import (
    LeakageError,
    Split,
    aligned,
    check_no_leakage,
    chrono_split,
    walk_forward,
)
from divvy_ml.synthetic import generate

DAY = dt.date(2026, 1, 1)


def days(*offsets):
    return [DAY + dt.timedelta(days=n) for n in offsets]


@pytest.fixture(scope="module")
def dates():
    return from_records([p.to_json() for p in generate(2000, seed=3)], Source.SYNTHETIC).dates


def test_chrono_split_boundaries_on_distinct_dates():
    split = chrono_split(days(*range(1000)), train=0.6, val=0.2)

    assert split.validation == range(600, 800)
    assert split.testing == range(800, 1000)


def test_boundaries_move_forward_off_a_shared_date():
    # indices 5 and 6 share a date, so a boundary at 6 moves to 7
    dates = days(0, 1, 2, 3, 4, 5, 5, 6, 7, 8)

    assert aligned(dates, 6) == 7
    assert aligned(dates, 5) == 5
    assert aligned(dates, 0) == 0
    assert aligned(dates, 10) == 10


def test_a_long_shared_date_can_swallow_the_boundary():
    dates = days(0, 1, 1, 1, 1, 1, 1, 1, 1, 2)

    assert aligned(dates, 4) == 9


@pytest.mark.parametrize(("train", "val"), [(0, 0.2), (0.8, 0.2), (1.2, 0)])
def test_rejects_bad_fractions(train, val):
    with pytest.raises(ValueError):
        chrono_split(days(*range(100)), train, val)


def test_no_test_date_is_earlier_than_any_training_date(dates):
    split = chrono_split(dates)

    check_no_leakage(dates, split)
    assert min(dates[split.test :]) > max(dates[: split.test])
    assert min(dates[split.val : split.test]) > max(dates[: split.val])


def test_no_date_is_split_across_a_boundary(dates):
    split = chrono_split(dates)

    for boundary in (split.val, split.test):
        assert dates[boundary - 1] < dates[boundary]


def test_walk_forward_covers_range_once_without_lookahead(dates):
    start = 400
    blocks = list(walk_forward(dates, start, len(dates), step=50))

    predicted = [i for fit_end, end in blocks for i in range(fit_end, end)]
    first = aligned(dates, start)
    assert predicted == list(range(first, len(dates)))
    for fit_end, end in blocks:
        assert max(dates[:fit_end]) < min(dates[fit_end:end])


def test_walk_forward_blocks_are_contiguous(dates):
    blocks = list(walk_forward(dates, 100, len(dates), step=30))

    assert all(
        a_end == b_start for (_, a_end), (b_start, _) in zip(blocks, blocks[1:], strict=False)
    )
    assert blocks[-1][1] == len(dates)


def test_walk_forward_matches_plain_blocks_when_dates_are_distinct():
    blocks = list(walk_forward(days(*range(145)), 100, 145, 20))

    assert blocks == [(100, 120), (120, 140), (140, 145)]


def test_walk_forward_rejects_a_bad_step():
    with pytest.raises(ValueError):
        list(walk_forward(days(0, 1, 2), 0, 3, 0))


def test_leakage_check_catches_a_naive_index_split():
    dates = days(0, 1, 2, 3, 3, 3, 4, 5, 6, 7)

    with pytest.raises(LeakageError, match="also in earlier data"):
        check_no_leakage(dates, Split(val=5, test=8, size=10))


def test_leakage_check_catches_unsorted_dates():
    with pytest.raises(LeakageError, match="chronological"):
        check_no_leakage(days(2, 1, 3), Split(val=1, test=2, size=3))


def test_leakage_check_catches_out_of_order_boundaries():
    with pytest.raises(LeakageError, match="out of order"):
        check_no_leakage(days(0, 1, 2, 3), Split(val=3, test=2, size=4))
