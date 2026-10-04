"""Names of the ownership models and how to build them."""

from collections.abc import Callable, Sequence

from divvy_ml.embed import Embedder, make_embedder
from divvy_ml.models.base import Model
from divvy_ml.models.baseline import ExactItemModel, FrequencyModel
from divvy_ml.models.calibration import CalibratedModel
from divvy_ml.models.knn import KNNModel
from divvy_ml.models.stacked import LogRegModel, StackedModel

DEFAULT_EMBEDDER = "hashing"

FACTORIES: dict[str, Callable[[Embedder], Model]] = {
    FrequencyModel.name: lambda embedder: FrequencyModel(),
    ExactItemModel.name: lambda embedder: ExactItemModel(),
    KNNModel.name: KNNModel,
    LogRegModel.name: LogRegModel,
    StackedModel.name: StackedModel,
    f"{KNNModel.name}-calibrated": lambda embedder: CalibratedModel(KNNModel(embedder)),
    f"{StackedModel.name}-calibrated": lambda embedder: CalibratedModel(StackedModel(embedder)),
}


def available_models() -> list[str]:
    return list(FACTORIES)


def make_models(
    names: Sequence[str] | None = None, embedder: Embedder | None = None
) -> list[Model]:
    """A fresh instance of each named model, or of every model when none are named."""
    chosen = list(names) if names else available_models()
    unknown = [n for n in chosen if n not in FACTORIES]
    if unknown:
        raise ValueError(f"unknown models {unknown}; choose from {available_models()}")

    shared = embedder or make_embedder(DEFAULT_EMBEDDER)
    return [FACTORIES[n](shared) for n in chosen]
