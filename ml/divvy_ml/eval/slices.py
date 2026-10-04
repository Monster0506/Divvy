"""Cold-start slices, computed from history strictly before each item.

Generated data carries product and category tags, so it gets all four slices. Other data only
tells whether the same item was bought before.
"""

from collections.abc import Sequence
from enum import StrEnum

from divvy_ml.eval.dataset import Observation

PRODUCT = "product"
CATEGORY = "category"


class Slice(StrEnum):
    SEEN = "seen"  # same normalised description appeared before
    RELATED = "related"  # same product, different wording
    NOVEL_PRODUCT = "novel_product"  # unseen product in a familiar category
    NOVEL_CATEGORY = "novel_category"  # nothing similar in history
    NEW = "new"  # not seen before; no product tags to say how new


def has_metadata(observations: Sequence[Observation]) -> bool:
    return all(PRODUCT in o.meta and CATEGORY in o.meta for o in observations)


def assign(observations: Sequence[Observation], start: int) -> list[Slice]:
    """Slice of each observation from `start` on, judged only on earlier observations."""
    tagged = has_metadata(observations)
    keys: set[str] = set()
    products: set[str] = set()
    categories: set[str] = set()
    found: list[Slice] = []

    for i, obs in enumerate(observations):
        if i >= start:
            found.append(_classify(obs, tagged, keys, products, categories))

        keys.add(obs.key)
        if tagged:
            products.add(obs.meta[PRODUCT])
            categories.add(obs.meta[CATEGORY])

    return found


def _classify(
    obs: Observation, tagged: bool, keys: set[str], products: set[str], categories: set[str]
) -> Slice:
    if obs.key in keys:
        return Slice.SEEN

    if not tagged:
        return Slice.NEW

    if obs.meta[PRODUCT] in products:
        return Slice.RELATED

    if obs.meta[CATEGORY] in categories:
        return Slice.NOVEL_PRODUCT

    return Slice.NOVEL_CATEGORY
