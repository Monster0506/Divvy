"""Chronological splits that never put a purchase date on both sides of a boundary.

Items bought on the same day come from the same trip, so they stay together. Dates must be sorted.
"""

import datetime as dt
from collections.abc import Iterator, Sequence
from dataclasses import dataclass

TRAIN_FRACTION = 0.6
VALIDATION_FRACTION = 0.2


class LeakageError(ValueError):
    pass


@dataclass(frozen=True)
class Split:
    """Index boundaries: [0, val) warm-up, [val, test) validation, [test, size) test."""

    val: int
    test: int
    size: int

    @property
    def validation(self) -> range:
        return range(self.val, self.test)

    @property
    def testing(self) -> range:
        return range(self.test, self.size)


def aligned(dates: Sequence[dt.date], index: int) -> int:
    """Move a boundary forward until it falls between two different purchase dates."""
    size = len(dates)
    while 0 < index < size and dates[index] == dates[index - 1]:
        index += 1

    return min(index, size)


def chrono_split(
    dates: Sequence[dt.date],
    train: float = TRAIN_FRACTION,
    val: float = VALIDATION_FRACTION,
) -> Split:
    if not 0 < train < 1 or not 0 <= val < 1 or train + val >= 1:
        raise ValueError("need 0 < train, 0 <= val, train + val < 1")

    size = len(dates)
    return Split(
        val=aligned(dates, int(size * train)),
        test=aligned(dates, int(size * (train + val))),
        size=size,
    )


def walk_forward(
    dates: Sequence[dt.date], start: int, stop: int, step: int
) -> Iterator[tuple[int, int]]:
    """Yield (fit_end, predict_end): fit on [0, fit_end), predict [fit_end, predict_end).

    Both ends fall on date boundaries, so every prediction uses only strictly earlier days.
    """
    if step < 1:
        raise ValueError("step must be positive")

    stop = aligned(dates, stop)
    fit_end = aligned(dates, start)
    while fit_end < stop:
        end = min(aligned(dates, fit_end + step), stop)
        yield fit_end, end
        fit_end = end


def check_no_leakage(dates: Sequence[dt.date], split: Split) -> None:
    """Raise unless every later period is strictly after every earlier one."""
    if list(dates) != sorted(dates):
        raise LeakageError("dates are not in chronological order")

    for name, boundary in (("validation", split.val), ("test", split.test)):
        if 0 < boundary < len(dates) and dates[boundary - 1] >= dates[boundary]:
            raise LeakageError(f"{name} starts on a date that is also in earlier data")

    if not split.val <= split.test <= split.size:
        raise LeakageError("split boundaries are out of order")
