"""Where the ownership service keeps its data, from the environment."""

import os
from pathlib import Path

DATA_DIR_ENV = "DIVVY_ML_DATA_DIR"
DEFAULT_DATA_DIR = "data"
MODEL_ENV = "DIVVY_MODEL"


def data_dir() -> Path:
    return Path(os.environ.get(DATA_DIR_ENV, DEFAULT_DATA_DIR))


def model_name(default: str) -> str:
    return os.environ.get(MODEL_ENV, default)
