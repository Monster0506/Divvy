from fastapi import FastAPI

from divvy_ml import __version__
from divvy_ml.receipts import router as receipts_router

app = FastAPI(title="Divvy ML", version=__version__)
app.include_router(receipts_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}
