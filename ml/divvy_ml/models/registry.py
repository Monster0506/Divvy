"""Names of the ownership models and how to build them."""

from collections.abc import Callable, Sequence

from divvy_ml.models.base import Model
from divvy_ml.models.baseline import ExactItemModel, FrequencyModel

FACTORIES: dict[str, Callable[[], Model]] = {
    FrequencyModel.name: FrequencyModel,
    ExactItemModel.name: ExactItemModel,
}


def available_models() -> list[str]:
    return list(FACTORIES)


def make_models(names: Sequence[str] | None = None) -> list[Model]:
    """A fresh instance of each named model, or of every model when none are named."""
    chosen = list(names) if names else available_models()
    unknown = [n for n in chosen if n not in FACTORIES]
    if unknown:
        raise ValueError(f"unknown models {unknown}; choose from {available_models()}")

    return [FACTORIES[n]() for n in chosen]
