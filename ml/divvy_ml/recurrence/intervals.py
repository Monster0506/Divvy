"""Per-product purchase interval statistics from purchase dates."""

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date
from statistics import median


@dataclass(frozen=True)
class IntervalStats:
    product_id: str
    purchase_count: int
    last_purchase: date
    median_interval_days: float | None
    interval_mad_days: float | None  # median absolute deviation of the intervals


def interval_stats(purchases: Iterable[tuple[str, date]]) -> dict[str, IntervalStats]:
    """Same-day purchases of a product count once. Intervals need at least two purchases."""
    days: dict[str, set[date]] = defaultdict(set)
    for product_id, day in purchases:
        days[product_id].add(day)

    result: dict[str, IntervalStats] = {}
    for product_id, product_days in days.items():
        ordered = sorted(product_days)
        gaps = [(b - a).days for a, b in zip(ordered, ordered[1:], strict=False)]
        mid = float(median(gaps)) if gaps else None
        spread = float(median(abs(g - mid) for g in gaps)) if mid is not None else None
        result[product_id] = IntervalStats(
            product_id=product_id,
            purchase_count=len(ordered),
            last_purchase=ordered[-1],
            median_interval_days=mid,
            interval_mad_days=spread,
        )
    return result
