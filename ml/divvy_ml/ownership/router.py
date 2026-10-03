from fastapi import APIRouter

from divvy_ml.ownership.schemas import (
    ItemPrediction,
    PredictionStatus,
    PredictRequest,
    PredictResponse,
)

router = APIRouter(prefix="/ownership", tags=["ownership"])

# Stub until a trained model is served (#114).
STUB_MODEL_VERSION = "stub-0"


@router.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    return PredictResponse(
        model_version=STUB_MODEL_VERSION,
        predictions=[
            ItemPrediction(item_id=item.item_id, status=PredictionStatus.UNKNOWN, confidence=0)
            for item in request.items
        ],
    )
