"""One fitted model per household, cached, versioned, and rebuilt when the history changes.

A model is derived data: it is fully determined by the household's history and the model name, so
its version is too, and only a small metadata file is persisted (see ADR 0002).

Refits never touch a live model. A new instance is fit off to the side and swapped in as a whole,
so a prediction sees either the old model or the new one, never a half-fitted one.
"""

import hashlib
import json
import logging
import os
import tempfile
import threading
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
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

logger = logging.getLogger("divvy_ml.refit")


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
    """Builds a household's model on first use and again whenever its history changes.

    With `refit_delay=None` a stale model is rebuilt on the next prediction, which then waits. With
    a delay in seconds, writes schedule a background refit that coalesces a burst into one, and
    predictions keep using the current model until the new one is swapped in.
    """

    def __init__(
        self,
        store: HistoryStore,
        embedder: Embedder,
        model_name: str = DEFAULT_MODEL,
        policy: AbstentionPolicy | None = None,
        models_dir: Path | None = None,
        max_history: int = MAX_HISTORY,
        refit_delay: float | None = None,
        model_factory: Callable[[], Model] | None = None,
    ) -> None:
        if model_name not in available_models():
            raise ValueError(f"unknown model {model_name!r}; choose from {available_models()}")

        self.store = store
        self.model_name = model_name
        self.policy = policy or AbstentionPolicy()
        self.builds: dict[str, int] = {}
        self.failures: dict[str, str] = {}
        self._embedder = embedder
        self._models_dir = models_dir
        self._max_history = max_history
        self._refit_delay = refit_delay
        self._model_factory = model_factory
        self._states: dict[str, ModelState] = {}
        self._guard = threading.Lock()
        self._build_locks: dict[str, threading.Lock] = {}
        self._timers: dict[str, threading.Timer] = {}
        self._running: dict[str, Future[None]] = {}
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="divvy-refit")
        self._closed = False

    @property
    def untrained_version(self) -> str:
        return f"{self.model_name}-untrained"

    def current_version(self, household_id: str) -> str | None:
        """Version of the model serving this household now, without building one."""
        state = self._states.get(household_id)
        return state.version if state else None

    def get(self, household_id: str) -> ModelState | None:
        """The household's model; None when it has too little history.

        The first model for a household is always built here. A stale one is served as is while a
        background refit runs, or rebuilt here when background refits are off.
        """
        check_household_id(household_id)
        state = self._states.get(household_id)
        if state and state.revision == self.store.revision(household_id):
            return state

        if state and self._refit_delay is not None:
            self._schedule(household_id)
            return state

        return self._rebuild(household_id)

    def notify_write(self, household_id: str) -> None:
        """Tell the manager the history changed, so a refit is queued (background mode only)."""
        if self._refit_delay is not None and not self._closed:
            self._schedule(household_id)

    def flush(self) -> None:
        """Run every queued refit now and wait for any that are running. For tests and shutdown."""
        with self._guard:
            timers = list(self._timers.items())
            self._timers.clear()
            running = list(self._running.values())

        for _, timer in timers:
            timer.cancel()

        for future in running:
            future.result()

        for household_id, _ in timers:
            self._background(household_id)

    def close(self) -> None:
        self._closed = True
        with self._guard:
            timers = list(self._timers.values())
            self._timers.clear()

        for timer in timers:
            timer.cancel()

        self._executor.shutdown(wait=True)

    def metadata(self, household_id: str) -> dict[str, Any] | None:
        """The persisted record of the household's latest model versions, if any."""
        if self._models_dir is None:
            return None

        path = self._meta_path(household_id)
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def _rebuild(self, household_id: str) -> ModelState | None:
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

    def _schedule(self, household_id: str) -> None:
        """Queue one refit after the delay. Writes arriving meanwhile share it."""
        assert self._refit_delay is not None
        with self._guard:
            if self._closed or household_id in self._timers:
                return

            timer = threading.Timer(self._refit_delay, self._fire, args=(household_id,))
            timer.daemon = True
            self._timers[household_id] = timer
            timer.start()

    def _fire(self, household_id: str) -> None:
        with self._guard:
            if self._timers.pop(household_id, None) is None or self._closed:
                return

            future = self._executor.submit(self._background, household_id)
            self._running[household_id] = future

    def _background(self, household_id: str) -> None:
        try:
            self._rebuild(household_id)
            self.failures.pop(household_id, None)
        except Exception as err:
            # the old model keeps serving; the next write or prediction tries again
            self.failures[household_id] = repr(err)
            logger.exception("refit failed for household %s", household_id)
        finally:
            with self._guard:
                self._running.pop(household_id, None)

        if self.store.revision(household_id) != self._revision_of(household_id):
            self._schedule(household_id)

    def _revision_of(self, household_id: str) -> int:
        state = self._states.get(household_id)
        return state.revision if state else -1

    def _build(self, household_id: str, dataset: Dataset, revision: int) -> ModelState:
        observations = dataset.observations[-self._max_history :]
        recent = Dataset(dataset.people, observations, dataset.source)
        model = self._new_model()
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
        self.builds[household_id] = self.builds.get(household_id, 0) + 1
        return state

    def _new_model(self) -> Model:
        if self._model_factory is not None:
            return self._model_factory()

        return make_models([self.model_name], self._embedder)[0]

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
