"""Per-household observation history, kept as one JSONL file per household.

Files use the format `python -m divvy_ml.eval` reads, so a household can be evaluated directly.
The store is the ML service's derived copy; the backend is the source of truth (ADR 0002).
"""

import json
import os
import re
import tempfile
import threading
from pathlib import Path
from typing import Any

from divvy_ml.eval.dataset import Dataset, Source, from_records
from divvy_ml.ownership.schemas import HOUSEHOLD_ID_PATTERN, ObservationIn

HOUSEHOLD_ID = re.compile(HOUSEHOLD_ID_PATTERN)
OBSERVATION_KEY = "observation_id"
ASSIGNMENT_KEY = "assignment"


class InvalidHouseholdId(ValueError):
    pass


def check_household_id(household_id: str) -> str:
    """Ids become file names, so only a safe character set is allowed."""
    if not HOUSEHOLD_ID.fullmatch(household_id):
        raise InvalidHouseholdId(f"invalid household id {household_id!r}")

    return household_id


class HistoryStore:
    """Thread-safe store. With no root directory it keeps everything in memory (for tests)."""

    def __init__(self, root: Path | None = None) -> None:
        self._root = root
        self._memory: dict[str, list[dict[str, Any]]] = {}
        self._lock = threading.Lock()
        if root is not None:
            root.mkdir(parents=True, exist_ok=True)

    def upsert(
        self, household_id: str, observations: list[ObservationIn], replace: bool = False
    ) -> int:
        """Add or update observations by id and return the history size."""
        with self._lock:
            records = [] if replace else self._read(household_id)
            by_id = {r["meta"][OBSERVATION_KEY]: r for r in records}
            next_seq = max((r["seq"] for r in records), default=0) + 1

            for obs in observations:
                existing = by_id.get(obs.observation_id)
                seq = existing["seq"] if existing else next_seq
                next_seq += 0 if existing else 1
                by_id[obs.observation_id] = _record(seq, obs)

            merged = sorted(by_id.values(), key=lambda r: (r["date"], r["seq"]))
            self._write(household_id, merged)
            return len(merged)

    def delete(self, household_id: str, observation_id: str) -> bool:
        with self._lock:
            records = self._read(household_id)
            kept = [r for r in records if r["meta"][OBSERVATION_KEY] != observation_id]
            if len(kept) == len(records):
                return False

            self._write(household_id, kept)
            return True

    def count(self, household_id: str) -> int:
        with self._lock:
            return len(self._read(household_id))

    def records(self, household_id: str) -> list[dict[str, Any]]:
        with self._lock:
            return self._read(household_id)

    def dataset(self, household_id: str) -> Dataset | None:
        """The household's history in chronological order, or None when it has none."""
        records = self.records(household_id)
        return from_records(records, Source.REAL) if records else None

    def _path(self, household_id: str) -> Path:
        assert self._root is not None
        return self._root / f"{check_household_id(household_id)}.jsonl"

    def _read(self, household_id: str) -> list[dict[str, Any]]:
        check_household_id(household_id)
        if self._root is None:
            return list(self._memory.get(household_id, []))

        path = self._path(household_id)
        if not path.exists():
            return []

        with path.open(encoding="utf-8") as handle:
            return [json.loads(line) for line in handle if line.strip()]

    def _write(self, household_id: str, records: list[dict[str, Any]]) -> None:
        if self._root is None:
            self._memory[household_id] = records
            return

        path = self._path(household_id)
        fd, tmp = tempfile.mkstemp(dir=self._root, suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
                handle.writelines(json.dumps(r) + "\n" for r in records)
            os.replace(tmp, path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise


def _record(seq: int, obs: ObservationIn) -> dict[str, Any]:
    owners = set(obs.owner_ids)
    return {
        "seq": seq,
        "date": obs.purchased_on.isoformat(),
        "item": obs.description.strip(),
        "owners": {pid: pid in owners for pid in obs.member_ids},
        "source": Source.REAL.value,
        "meta": {OBSERVATION_KEY: obs.observation_id, ASSIGNMENT_KEY: obs.source.value},
    }
