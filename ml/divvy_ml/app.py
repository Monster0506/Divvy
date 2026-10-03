from fastapi import FastAPI

from divvy_ml import __version__

app = FastAPI(title="Divvy ML", version=__version__)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}
