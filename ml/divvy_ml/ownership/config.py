"""Where the ownership service keeps its data, from the environment."""

import os
from pathlib import Path

DATA_DIR_ENV = "DIVVY_ML_DATA_DIR"
DEFAULT_DATA_DIR = "data"
MODEL_ENV = "DIVVY_MODEL"
REFIT_DELAY_ENV = "DIVVY_REFIT_DELAY"
DEFAULT_REFIT_DELAY = 1.0


def data_dir() -> Path:
    return Path(os.environ.get(DATA_DIR_ENV, DEFAULT_DATA_DIR))


def model_name(default: str) -> str:
    return os.environ.get(MODEL_ENV, default)


def refit_delay() -> float | None:
    """Seconds writes wait before a background refit; "sync" turns background refits off."""
    value = os.environ.get(REFIT_DELAY_ENV, str(DEFAULT_REFIT_DELAY)).strip().lower()
    return None if value in {"", "sync", "off", "none"} else float(value)
