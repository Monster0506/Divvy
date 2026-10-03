"""Simulate the persona household buying groceries, one shopping trip every few days.

Each active habit adds a Poisson number of purchases per trip. Output is seeded and reproducible.
"""

import datetime as dt
import math
import random
from collections.abc import Sequence
from dataclasses import dataclass, field
from functools import cache
from itertools import combinations
from typing import Any

from divvy_ml.synthetic.personas import HABITS, PEOPLE, PRODUCTS, Habit, Product, allowed_owners

SEED = 7
DEFAULT_SIZE = 6000
START_DATE = dt.date(2024, 1, 1)
TRIP_GAP_DAYS = (1, 4)
DAYS_PER_WEEK = 7
VARIANT_DECAY = 0.6
DEVIATION_RATE = 0.03
SOURCE = "synthetic"

PRODUCT = "product"
CATEGORY = "category"
HABIT = "habit"

OWNER_SETS = [frozenset(c) for r in range(1, len(PEOPLE) + 1) for c in combinations(PEOPLE, r)]


@dataclass(frozen=True)
class Purchase:
    """One purchased item and the people it belongs to. `meta` records how it was generated."""

    seq: int
    date: dt.date
    item: str
    owners: frozenset[str]
    meta: dict[str, str] = field(default_factory=dict, compare=False, hash=False)

    def to_json(self) -> dict[str, Any]:
        return {
            "seq": self.seq,
            "date": self.date.isoformat(),
            "item": self.item,
            "owners": {name: name in self.owners for name in PEOPLE},
            "source": SOURCE,
            "meta": self.meta,
        }


@cache
def owner_distribution(
    owners: frozenset[str], allowed: tuple[str, ...]
) -> dict[frozenset[str], float]:
    """Habit owners, or with DEVIATION_RATE one allowed person toggled in or out."""
    dist = {s: 0.0 for s in OWNER_SETS}
    dist[owners] += 1 - DEVIATION_RATE
    share = DEVIATION_RATE / len(allowed)

    for name in allowed:
        toggled = owners ^ {name}
        if toggled:
            dist[toggled] += share
            continue

        # toggling the only owner away hands the item to another allowed person, if any
        others = [n for n in allowed if n != name] or [name]
        for other in others:
            dist[frozenset({other})] += share / len(others)

    return dist


def generate(size: int = DEFAULT_SIZE, seed: int = SEED) -> list[Purchase]:
    rng = random.Random(seed)
    purchases: list[Purchase] = []
    date = START_DATE

    while len(purchases) < size:
        gap = rng.randint(*TRIP_GAP_DAYS)
        date += dt.timedelta(days=gap)
        progress = len(purchases) / size

        trip = [
            h
            for h in HABITS
            if _active(h, progress)
            for _ in range(_poisson(rng, h.per_week * gap / DAYS_PER_WEEK))
        ]
        rng.shuffle(trip)

        for habit in trip[: size - len(purchases)]:
            purchases.append(_purchase(rng, habit, date, len(purchases) + 1, progress))

    return purchases


def is_generated(purchases: Sequence[Purchase]) -> bool:
    """True when every purchase came from this simulator's habits."""
    labels = {h.label for h in HABITS}
    return all(p.meta.get(HABIT) in labels for p in purchases)


def bayes_ceiling(purchases: Sequence[Purchase], start: int = 0) -> float:
    """Best exact-set accuracy on purchases[start:] for a model that knows every habit.

    Needs the full generated sequence so trip timing can be recovered.
    """
    trip_start: dict[dt.date, int] = {}
    for i, p in enumerate(purchases):
        trip_start.setdefault(p.date, i)

    scored = purchases[start:]
    if not scored:
        return 0.0

    total = sum(_best_guess(p.item, trip_start[p.date] / len(purchases)) for p in scored)
    return total / len(scored)


def _best_guess(text: str, progress: float) -> float:
    mixture = {s: 0.0 for s in OWNER_SETS}
    for habit in HABITS:
        if not _active(habit, progress):
            continue

        product = PRODUCTS[habit.product]
        texts, weights = _wordings(product, progress)
        if text not in texts:
            continue

        weight = habit.per_week * weights[texts.index(text)] / sum(weights)
        for owners, p in owner_distribution(habit.owner_set, allowed_owners(product)).items():
            mixture[owners] += weight * p

    return max(mixture.values()) / sum(mixture.values())


def _active(habit: Habit, progress: float) -> bool:
    return habit.intro <= progress < habit.until


def _poisson(rng: random.Random, mean: float) -> int:
    limit, count, product = math.exp(-mean), 0, rng.random()
    while product > limit:
        count += 1
        product *= rng.random()

    return count


def _purchase(
    rng: random.Random, habit: Habit, date: dt.date, seq: int, progress: float
) -> Purchase:
    product = PRODUCTS[habit.product]
    dist = owner_distribution(habit.owner_set, allowed_owners(product))
    owners = rng.choices(list(dist), weights=list(dist.values()))[0]

    texts, weights = _wordings(product, progress)
    item = rng.choices(texts, weights=weights)[0]

    meta = {PRODUCT: product.name, CATEGORY: product.category, HABIT: habit.label}
    return Purchase(seq, date, item, owners, meta)


def _wordings(product: Product, progress: float) -> tuple[list[str], list[float]]:
    texts, weights = [], []
    for rank, variant in enumerate(product.variants):
        text, intro = variant if isinstance(variant, tuple) else (variant, 0.0)
        if intro <= progress:
            texts.append(text)
            weights.append(VARIANT_DECAY**rank)

    return texts, weights
