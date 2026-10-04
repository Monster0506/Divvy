from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from divvy_ml import __version__
from divvy_ml.embed import configured_embedder
from divvy_ml.embed.router import router as embed_router
from divvy_ml.normalize.router import router as normalize_router
from divvy_ml.ownership import router as ownership_router
from divvy_ml.ownership.config import (
    data_dir,
    model_name,
    refit_delay,
    retrain_after,
    retrain_interval,
)
from divvy_ml.ownership.manager import DEFAULT_MODEL, ModelManager
from divvy_ml.ownership.retrain import Retrainer
from divvy_ml.ownership.store import HistoryStore
from divvy_ml.receipts import router as receipts_router


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Load the embedder once, open the household history and model cache, and start retraining."""
    root = data_dir()
    app.state.embedder = configured_embedder()
    app.state.history = HistoryStore(root / "history")
    app.state.models = ModelManager(
        app.state.history,
        app.state.embedder,
        model_name(DEFAULT_MODEL),
        models_dir=root / "models",
        refit_delay=refit_delay(),
    )
    interval = retrain_interval()
    app.state.retrainer = None
    if interval is not None:
        app.state.retrainer = Retrainer(app.state.models, interval, retrain_after())
        app.state.retrainer.start()
    yield
    if app.state.retrainer is not None:
        app.state.retrainer.stop()
    app.state.models.close()


app = FastAPI(title="Divvy ML", version=__version__, lifespan=lifespan)
app.include_router(receipts_router)
app.include_router(normalize_router)
app.include_router(ownership_router)
app.include_router(embed_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}
