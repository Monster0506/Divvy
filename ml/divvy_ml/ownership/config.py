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


RETRAIN_INTERVAL_ENV = "DIVVY_RETRAIN_INTERVAL"
RETRAIN_AFTER_ENV = "DIVVY_RETRAIN_AFTER"
DEFAULT_RETRAIN_INTERVAL = 86400.0
DEFAULT_RETRAIN_AFTER = 50


def retrain_interval() -> float | None:
    """Seconds between scheduled retrains (default nightly); "off" disables the schedule."""
    value = os.environ.get(RETRAIN_INTERVAL_ENV, str(DEFAULT_RETRAIN_INTERVAL)).strip().lower()
    return None if value in {"", "off", "none", "0"} else float(value)


def retrain_after() -> int:
    """New observations that trigger a retrain without waiting for the interval."""
    return int(os.environ.get(RETRAIN_AFTER_ENV, str(DEFAULT_RETRAIN_AFTER)))
