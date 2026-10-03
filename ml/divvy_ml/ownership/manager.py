"""One fitted model per household, cached, versioned, and rebuilt when the history changes.

A model is derived data: it is fully determined by the household's history and the model name, so
its version is too, and only a small metadata file is persisted (see ADR 0002).
"""

import hashlib
import json
import os
import tempfile
import threading
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from divvy_ml.embed import Embedder
from divvy_ml.eval.dataset import Dataset
from divvy_ml.models import Model, available_models, make_models
from divvy_ml.models.abstain import AbstentionPolicy
from divvy_ml.ownership.store import HistoryStore, check_household_id

DEFAULT_MODEL = "combined-calibrated"
MAX_HISTORY = 5000
KEPT_VERSIONS = 10


@dataclass(frozen=True)
class ModelState:
    version: str
    model: Model
    people: tuple[str, ...]
    history_size: int
    revision: int
    trained_at: str
    metrics: dict[str, Any]


class ModelManager:
    """Builds a household's model on first use and again whenever its history changes."""

    def __init__(
        self,
        store: HistoryStore,
        embedder: Embedder,
        model_name: str = DEFAULT_MODEL,
        policy: AbstentionPolicy | None = None,
        models_dir: Path | None = None,
        max_history: int = MAX_HISTORY,
    ) -> None:
        if model_name not in available_models():
            raise ValueError(f"unknown model {model_name!r}; choose from {available_models()}")

        self.store = store
        self.model_name = model_name
        self.policy = policy or AbstentionPolicy()
        self._embedder = embedder
        self._models_dir = models_dir
        self._max_history = max_history
        self._states: dict[str, ModelState] = {}
        self._guard = threading.Lock()
        self._build_locks: dict[str, threading.Lock] = {}

    @property
    def untrained_version(self) -> str:
        return f"{self.model_name}-untrained"

    def current_version(self, household_id: str) -> str | None:
        """Version of the model serving this household now, without building one."""
        state = self._states.get(household_id)
        return state.version if state else None

    def get(self, household_id: str) -> ModelState | None:
        """The household's model, built or rebuilt if needed; None with too little history."""
        check_household_id(household_id)
        state = self._states.get(household_id)
        if state and state.revision == self.store.revision(household_id):
            return state

        with self._lock_for(household_id):
            revision, dataset = self.store.snapshot(household_id)
            state = self._states.get(household_id)
            if state and state.revision == revision:
                return state

            if dataset is None or len(dataset) < self.policy.min_history:
                self._states.pop(household_id, None)
                return None

            state = self._build(household_id, dataset, revision)
            self._states[household_id] = state
            return state

    def metadata(self, household_id: str) -> dict[str, Any] | None:
        """The persisted record of the household's latest model versions, if any."""
        if self._models_dir is None:
            return None

        path = self._meta_path(household_id)
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def _build(self, household_id: str, dataset: Dataset, revision: int) -> ModelState:
        observations = dataset.observations[-self._max_history :]
        recent = Dataset(dataset.people, observations, dataset.source)
        model = make_models([self.model_name], self._embedder)[0]
        model.fit(recent.items, recent.labels(), recent.known())

        state = ModelState(
            version=self._version(recent),
            model=model,
            people=recent.people,
            history_size=len(recent),
            revision=revision,
            trained_at=datetime.now(UTC).isoformat(timespec="seconds"),
            metrics=_metrics(model),
        )
        self._persist(household_id, state)
        return state

    def _version(self, dataset: Dataset) -> str:
        digest = hashlib.sha1(usedforsecurity=False)
        for obs in dataset.observations:
            digest.update(f"{obs.seq}|{obs.date}|{obs.item}|{sorted(obs.owners)}\n".encode())

        return f"{self.model_name}-{len(dataset)}-{digest.hexdigest()[:8]}"

    def _persist(self, household_id: str, state: ModelState) -> None:
        if self._models_dir is None:
            return

        record = {
            "version": state.version,
            "model": self.model_name,
            "trained_at": state.trained_at,
            "history_size": state.history_size,
            "people": list(state.people),
            "metrics": state.metrics,
        }
        previous = self.metadata(household_id) or {}
        versions = [*previous.get("versions", []), record][-KEPT_VERSIONS:]
        path = self._meta_path(household_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump({"current": record, "versions": versions}, handle, indent=2)

        os.replace(tmp, path)

    def _meta_path(self, household_id: str) -> Path:
        assert self._models_dir is not None
        return self._models_dir / household_id / "meta.json"

    def _lock_for(self, household_id: str) -> threading.Lock:
        with self._guard:
            return self._build_locks.setdefault(household_id, threading.Lock())


def _metrics(model: Model) -> dict[str, Any]:
    holdout = getattr(model, "holdout", None)
    return {
        "calibrated": bool(getattr(model, "calibrated", False)),
        "holdout": holdout,
    }
