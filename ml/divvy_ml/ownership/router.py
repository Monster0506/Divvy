from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Path, Request, Response

from divvy_ml.embed.router import app_embedder
from divvy_ml.models.abstain import MIN_HISTORY
from divvy_ml.ownership import service
from divvy_ml.ownership.config import data_dir, model_name, refit_delay
from divvy_ml.ownership.manager import DEFAULT_MODEL, ModelManager
from divvy_ml.ownership.schemas import (
    HOUSEHOLD_ID_PATTERN,
    HistoryStatus,
    PredictRequest,
    PredictResponse,
    UpsertObservationsRequest,
    UpsertObservationsResponse,
)
from divvy_ml.ownership.store import HistoryStore

router = APIRouter(prefix="/ownership", tags=["ownership"])

HouseholdPath = Annotated[str, Path(pattern=HOUSEHOLD_ID_PATTERN)]


def build_manager(store: HistoryStore, request: Request) -> ModelManager:
    return ModelManager(
        store,
        app_embedder(request),
        model_name(DEFAULT_MODEL),
        models_dir=data_dir() / "models",
        refit_delay=refit_delay(),
    )


def get_store(request: Request) -> HistoryStore:
    """The history store created at startup, or created now if the app ran without lifespan."""
    store = getattr(request.app.state, "history", None)
    if store is None:
        store = request.app.state.history = HistoryStore(data_dir() / "history")

    return store


Store = Annotated[HistoryStore, Depends(get_store)]


def get_manager(request: Request, store: Store) -> ModelManager:
    manager = getattr(request.app.state, "models", None)
    if manager is None:
        manager = request.app.state.models = build_manager(store, request)

    return manager


Manager = Annotated[ModelManager, Depends(get_manager)]


@router.post("/predict", response_model=PredictResponse)
def predict(body: PredictRequest, manager: Manager) -> PredictResponse:
    """Predict who owns each item from the household's stored history.

    Items come back as `unknown` while the household has too little history, or when nothing in it
    resembles the item.
    """
    state = manager.get(body.household_id)
    if state is None:
        return service.unknown_response(body, manager.untrained_version)

    return service.predict(state, body, manager.policy)


@router.put("/households/{household_id}/observations", response_model=UpsertObservationsResponse)
def upsert_observations(
    household_id: HouseholdPath,
    body: UpsertObservationsRequest,
    store: Store,
    manager: Manager,
    replace: bool = False,
) -> UpsertObservationsResponse:
    """Add or update confirmed observations by id; `replace=true` swaps the whole history."""
    size = store.upsert(household_id, body.observations, replace=replace)
    manager.notify_write(household_id)
    return UpsertObservationsResponse(
        household_id=household_id, received=len(body.observations), history_size=size
    )


@router.delete("/households/{household_id}/observations/{observation_id}", status_code=204)
def delete_observation(
    household_id: HouseholdPath, observation_id: str, store: Store, manager: Manager
) -> Response:
    if not store.delete(household_id, observation_id):
        raise HTTPException(status_code=404, detail="observation not found")

    manager.notify_write(household_id)
    return Response(status_code=204)


@router.get("/households/{household_id}/history", response_model=HistoryStatus)
def history_status(household_id: HouseholdPath, store: Store, manager: Manager) -> HistoryStatus:
    size = store.count(household_id)
    return HistoryStatus(
        household_id=household_id,
        history_size=size,
        min_history=MIN_HISTORY,
        ready=size >= MIN_HISTORY,
        model_version=manager.current_version(household_id),
    )
