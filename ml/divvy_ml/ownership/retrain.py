"""Scheduled per-household retraining.

A household is retrained once its history has grown by `after` observations, or once `interval`
seconds have passed since its last scheduled run and it has any new observations. Every run is
logged and kept in a short in-memory list, which the API exposes.
"""

import logging
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Any

from divvy_ml.ownership.manager import ModelManager

logger = logging.getLogger("divvy_ml.retrain")

MAX_RUNS = 100
CHECK_SECONDS = 60.0


@dataclass(frozen=True)
class RetrainRun:
    household_id: str
    started_at: str
    finished_at: str
    seconds: float
    history_size: int
    new_observations: int
    reason: str
    version: str | None
    metrics: dict[str, Any] | None
    error: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


class Retrainer:
    def __init__(
        self,
        manager: ModelManager,
        interval: float = 86400.0,
        after: int = 50,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._manager = manager
        self._interval = interval
        self._after = after
        self._clock = clock
        self._seen: dict[str, tuple[int, float]] = {}
        self._runs: deque[RetrainRun] = deque(maxlen=MAX_RUNS)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(target=self._loop, name="divvy-retrain", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def runs(self) -> list[RetrainRun]:
        """Recent runs, newest first."""
        with self._lock:
            return list(reversed(self._runs))

    def run_once(self) -> list[RetrainRun]:
        """Retrain every household that is due and return the runs made."""
        made = []
        for household_id in self._manager.store.households():
            reason = self._due(household_id)
            if reason:
                made.append(self._run(household_id, reason))

        return made

    def _due(self, household_id: str) -> str | None:
        size = self._manager.store.count(household_id)
        trained_size, trained_at = self._seen.get(household_id, (0, self._clock()))
        new = size - trained_size
        if household_id not in self._seen and new > 0:
            return "first"

        if new >= self._after:
            return "new-observations"

        if new > 0 and self._clock() - trained_at >= self._interval:
            return "interval"

        return None

    def _run(self, household_id: str, reason: str) -> RetrainRun:
        before = self._seen.get(household_id, (0, 0.0))[0]
        size = self._manager.store.count(household_id)
        started = datetime.now(UTC)
        began = time.perf_counter()
        logger.info("retrain start household=%s reason=%s history=%d", household_id, reason, size)

        version = metrics = error = None
        try:
            state = self._manager.retrain(household_id)
            if state is not None:
                version, metrics, size = state.version, state.metrics, state.history_size
            self._seen[household_id] = (self._manager.store.count(household_id), self._clock())
        except Exception as err:
            error = repr(err)
            logger.exception("retrain failed for household %s", household_id)

        run = RetrainRun(
            household_id=household_id,
            started_at=started.isoformat(timespec="seconds"),
            finished_at=datetime.now(UTC).isoformat(timespec="seconds"),
            seconds=round(time.perf_counter() - began, 3),
            history_size=size,
            new_observations=size - before,
            reason=reason,
            version=version,
            metrics=metrics,
            error=error,
        )
        logger.info(
            "retrain end household=%s seconds=%.3f history=%d version=%s metrics=%s error=%s",
            household_id,
            run.seconds,
            size,
            version,
            metrics,
            error,
        )
        with self._lock:
            self._runs.append(run)

        return run

    def _loop(self) -> None:
        tick = min(self._interval, CHECK_SECONDS)
        while not self._stop.wait(tick):
            try:
                self.run_once()
            except Exception:
                logger.exception("retrain pass failed")
