from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Path, Request, Response

from divvy_ml.models.abstain import MIN_HISTORY
from divvy_ml.ownership.config import data_dir
from divvy_ml.ownership.schemas import (
    HOUSEHOLD_ID_PATTERN,
    HistoryStatus,
    ItemPrediction,
    PredictionStatus,
    PredictRequest,
    PredictResponse,
    UpsertObservationsRequest,
    UpsertObservationsResponse,
)
from divvy_ml.ownership.store import HistoryStore

router = APIRouter(prefix="/ownership", tags=["ownership"])

# Stub until a trained model is served (#114).
STUB_MODEL_VERSION = "stub-0"

HouseholdPath = Annotated[str, Path(pattern=HOUSEHOLD_ID_PATTERN)]


def get_store(request: Request) -> HistoryStore:
    """The history store created at startup, or created now if the app ran without lifespan."""
    store = getattr(request.app.state, "history", None)
    if store is None:
        store = request.app.state.history = HistoryStore(data_dir() / "history")

    return store


Store = Annotated[HistoryStore, Depends(get_store)]


@router.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    return PredictResponse(
        model_version=STUB_MODEL_VERSION,
        predictions=[
            ItemPrediction(item_id=item.item_id, status=PredictionStatus.UNKNOWN, confidence=0)
            for item in request.items
        ],
    )


@router.put("/households/{household_id}/observations", response_model=UpsertObservationsResponse)
def upsert_observations(
    household_id: HouseholdPath,
    body: UpsertObservationsRequest,
    store: Store,
    replace: bool = False,
) -> UpsertObservationsResponse:
    """Add or update confirmed observations by id; `replace=true` swaps the whole history."""
    size = store.upsert(household_id, body.observations, replace=replace)
    return UpsertObservationsResponse(
        household_id=household_id, received=len(body.observations), history_size=size
    )


@router.delete("/households/{household_id}/observations/{observation_id}", status_code=204)
def delete_observation(household_id: HouseholdPath, observation_id: str, store: Store) -> Response:
    if not store.delete(household_id, observation_id):
        raise HTTPException(status_code=404, detail="observation not found")

    return Response(status_code=204)


@router.get("/households/{household_id}/history", response_model=HistoryStatus)
def history_status(household_id: HouseholdPath, store: Store) -> HistoryStatus:
    size = store.count(household_id)
    return HistoryStatus(
        household_id=household_id,
        history_size=size,
        min_history=MIN_HISTORY,
        ready=size >= MIN_HISTORY,
    )
