import logging
from functools import lru_cache
from typing import Annotated, Protocol

from fastapi import APIRouter, Depends, HTTPException

from divvy_ml.normalize.configured import configured_normalizer
from divvy_ml.qwen_config import NotConfiguredError, QwenConfig
from divvy_ml.receipts import images
from divvy_ml.receipts.images import ImageError, ReceiptImage, image_dir
from divvy_ml.receipts.qwen import ExtractionError, QwenExtractor
from divvy_ml.receipts.schemas import ExtractedReceipt, ExtractRequest

log = logging.getLogger(__name__)

router = APIRouter(prefix="/receipts", tags=["receipts"])


class Extractor(Protocol):
    def extract(self, image: ReceiptImage) -> ExtractedReceipt: ...


@lru_cache(maxsize=1)
def _configured_extractor() -> QwenExtractor:
    return QwenExtractor(QwenConfig.from_env(), normalizer=configured_normalizer())


def get_extractor() -> Extractor:
    """The Qwen extractor from the environment. Tests replace this dependency."""
    try:
        return _configured_extractor()
    except NotConfiguredError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from None


@router.post(
    "/extract",
    response_model=ExtractedReceipt,
    responses={
        422: {"description": "The image is missing, unreadable or not a supported type"},
        502: {"description": "The vision service failed or returned something unusable"},
        503: {"description": "Extraction is not configured (no API key)"},
    },
)
def extract(
    request: ExtractRequest, extractor: Annotated[Extractor, Depends(get_extractor)]
) -> ExtractedReceipt:
    try:
        image = (
            images.from_base64(request.image_base64)
            if request.image_base64 is not None
            else images.from_stored_id(request.image_id or "", image_dir())
        )
    except ImageError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from None
    try:
        return extractor.extract(image)
    except ExtractionError as exc:
        log.warning("Extraction failed: %s", exc)
        raise HTTPException(status_code=502, detail=str(exc)) from None
