from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from divvy_ml import __version__
from divvy_ml.embed import configured_embedder
from divvy_ml.embed.router import router as embed_router
from divvy_ml.ownership import router as ownership_router
from divvy_ml.receipts import router as receipts_router


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.embedder = configured_embedder()
    yield


app = FastAPI(title="Divvy ML", version=__version__, lifespan=lifespan)
app.include_router(receipts_router)
app.include_router(ownership_router)
app.include_router(embed_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}
