from typing import Annotated

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field

from divvy_ml.embed.base import Embedder
from divvy_ml.embed.registry import configured_embedder

MAX_TEXTS = 256
MAX_LENGTH = 512

router = APIRouter(tags=["embeddings"])

Text = Annotated[str, Field(min_length=1, max_length=MAX_LENGTH)]


class EmbedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    texts: list[Text] = Field(min_length=1, max_length=MAX_TEXTS, description="Item descriptions")


class EmbedResponse(BaseModel):
    embedder: str = Field(description="Which embedder produced the vectors")
    dimensions: int
    embeddings: list[list[float]] = Field(description="One L2-normalised vector per text, in order")


def app_embedder(request: Request) -> Embedder:
    """The embedder loaded at startup, or loaded now if the app was started without lifespan."""
    embedder = getattr(request.app.state, "embedder", None)
    if embedder is None:
        embedder = request.app.state.embedder = configured_embedder()

    return embedder


get_embedder = app_embedder


@router.post("/embed", response_model=EmbedResponse)
def embed(
    body: EmbedRequest, embedder: Annotated[Embedder, Depends(get_embedder)]
) -> EmbedResponse:
    vectors = embedder.embed(body.texts)
    return EmbedResponse(
        embedder=embedder.name,
        dimensions=int(vectors.shape[1]),
        embeddings=vectors.tolist(),
    )
