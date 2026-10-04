from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field

from divvy_ml.normalize.configured import configured_normalizer
from divvy_ml.normalize.normalizer import NameNormalizer

router = APIRouter(prefix="/normalize", tags=["normalize"])


class NormalizeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    names: list[Annotated[str, Field(max_length=200)]] = Field(min_length=1, max_length=200)


class NormalizedName(BaseModel):
    printed: str
    name: str = Field(description="Readable lowercase name; empty when only codes and sizes remain")


class NormalizeResponse(BaseModel):
    names: list[NormalizedName]


def get_normalizer() -> NameNormalizer:
    """Tests replace this dependency."""
    return configured_normalizer()


@router.post("", response_model=NormalizeResponse)
def normalize(
    request: NormalizeRequest, normalizer: Annotated[NameNormalizer, Depends(get_normalizer)]
) -> NormalizeResponse:
    """Readable names for printed receipt lines. Falls back to rules when no LLM is available."""
    names = normalizer.normalize_many(request.names)
    return NormalizeResponse(
        names=[
            NormalizedName(printed=raw, name=name)
            for raw, name in zip(request.names, names, strict=True)
        ]
    )
