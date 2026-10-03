"""Labelled ownership observations: JSONL loading, saving and whole-file validation.

One line per purchase: seq, date, item, owners (person -> bool), source and optional meta.
`owners` lists the people in the household at that time; anyone missing is unknown, not a "no".
Observations are ordered by purchase date, then seq. Real and synthetic rows never mix.
"""

import datetime as dt
import json
import re
from collections import Counter
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field, replace
from enum import StrEnum
from pathlib import Path
from typing import Any

import numpy as np

_NON_ALNUM = re.compile(r"[^a-z0-9]+")
REQUIRED_FIELDS = {"seq", "date", "item", "owners", "source"}


class Source(StrEnum):
    REAL = "real"
    SYNTHETIC = "synthetic"


class DatasetError(ValueError):
    pass


def normalize_item(text: str) -> str:
    """Key used to decide whether two descriptions are the same item."""
    return _NON_ALNUM.sub(" ", text.lower()).strip()


@dataclass(frozen=True)
class Observation:
    seq: int
    date: dt.date
    item: str
    owners: frozenset[str]
    source: Source
    meta: dict[str, str] = field(default_factory=dict, compare=False, hash=False)
    members: frozenset[str] | None = None  # people in the household then; None means everyone

    @property
    def key(self) -> str:
        return normalize_item(self.item)


@dataclass(frozen=True)
class Dataset:
    """Observations in chronological order for a fixed set of people."""

    people: tuple[str, ...]
    observations: tuple[Observation, ...]
    source: Source

    def __len__(self) -> int:
        return len(self.observations)

    @property
    def items(self) -> list[str]:
        return [o.item for o in self.observations]

    @property
    def dates(self) -> list[dt.date]:
        return [o.date for o in self.observations]

    def labels(self) -> np.ndarray:
        """One row per observation, one 0/1 column per person, in `people` order."""
        rows = [[int(name in o.owners) for name in self.people] for o in self.observations]
        return np.array(rows, dtype=int).reshape(-1, len(self.people))

    def known(self) -> np.ndarray:
        """True where the person was in the household for that observation."""
        rows = [
            [o.members is None or name in o.members for name in self.people]
            for o in self.observations
        ]
        return np.array(rows, dtype=bool).reshape(-1, len(self.people))

    def records(self) -> list[dict[str, Any]]:
        return [_to_record(o, self.people) for o in self.observations]


def chrono_key(obs: Observation) -> tuple[dt.date, int]:
    return (obs.date, obs.seq)


def from_records(records: Iterable[Any], expect: Source | None = None) -> Dataset:
    """Build a dataset from already-parsed records, e.g. rows exported from the database."""
    return _build(list(enumerate(records, start=1)), expect)


def parse_lines(lines: Iterable[str], expect: Source | None = None) -> Dataset:
    parsed: list[tuple[int, Any]] = []
    problems = []

    for number, line in enumerate(lines, start=1):
        if not line.strip():
            continue

        try:
            parsed.append((number, json.loads(line)))
        except json.JSONDecodeError as err:
            problems.append(f"line {number}: invalid JSON ({err.msg})")

    if problems:
        try:
            _build(parsed, expect)
        except DatasetError as err:
            problems.append(str(err))

        raise DatasetError("\n".join(problems))

    return _build(parsed, expect)


def load(path: Path, expect: Source | None = None) -> Dataset:
    with open(path, encoding="utf-8") as handle:
        return parse_lines(handle, expect)


def save(path: Path, dataset: Dataset) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        for record in dataset.records():
            handle.write(json.dumps(record) + "\n")


def from_observations(
    observations: Sequence[Observation], people: Sequence[str], source: Source
) -> Dataset:
    """Order observations chronologically and check they fit the people and source."""
    ordered = tuple(sorted(observations, key=chrono_key))
    problems = _file_problems(ordered, expect=source)
    stray = {name for o in ordered for name in o.owners | (o.members or frozenset())} - set(people)
    if stray:
        problems.append(f"owners outside the household: {sorted(stray)}")

    if problems:
        raise DatasetError("; ".join(problems))

    return Dataset(tuple(people), _canonical(ordered, set(people)), source)


def _build(parsed: list[tuple[int, Any]], expect: Source | None) -> Dataset:
    observations = []
    problems = []
    people: dict[str, None] = {}

    for number, record in parsed:
        try:
            owners_by_person = _owner_flags(record)
            people.update(dict.fromkeys(owners_by_person))
            observations.append(_parse_observation(record, owners_by_person))
        except DatasetError as err:
            problems.append(f"line {number}: {err}")

    if problems:
        raise DatasetError("\n".join(problems))

    if not observations:
        raise DatasetError("dataset has no observations")

    ordered = tuple(sorted(observations, key=chrono_key))
    file_problems = _file_problems(ordered, expect)
    if file_problems:
        raise DatasetError("; ".join(file_problems))

    return Dataset(tuple(people), _canonical(ordered, set(people)), ordered[0].source)


def _canonical(observations: Sequence[Observation], people: set[str]) -> tuple[Observation, ...]:
    """Store "everyone was a member" as None so equal households compare equal."""
    return tuple(replace(o, members=None) if o.members == people else o for o in observations)


def _file_problems(observations: Sequence[Observation], expect: Source | None) -> list[str]:
    problems = []
    dupes = [seq for seq, n in Counter(o.seq for o in observations).items() if n > 1]
    if dupes:
        problems.append(f"duplicate seq values: {sorted(dupes)[:10]}")

    sources = {o.source for o in observations}
    if len(sources) > 1:
        problems.append("real and synthetic observations must live in separate files")

    if expect and sources - {expect}:
        problems.append(f"expected only {expect.value} observations")

    return problems


def _owner_flags(record: Any) -> dict[str, bool]:
    if not isinstance(record, dict):
        raise DatasetError("record must be a JSON object")

    missing = REQUIRED_FIELDS - record.keys()
    if missing:
        raise DatasetError(f"missing fields: {sorted(missing)}")

    owners = record["owners"]
    if not isinstance(owners, dict) or not owners:
        raise DatasetError("owners must be an object of person -> bool")

    if not all(isinstance(flag, bool) for flag in owners.values()):
        raise DatasetError("owner flags must be true/false")

    if not any(owners.values()):
        raise DatasetError("at least one person must own the item")

    return owners


def _parse_observation(record: dict[str, Any], owners_by_person: dict[str, bool]) -> Observation:
    seq = record["seq"]
    if not isinstance(seq, int) or isinstance(seq, bool) or seq < 1:
        raise DatasetError(f"seq must be a positive integer, got {seq!r}")

    try:
        date = dt.date.fromisoformat(record["date"])
    except (TypeError, ValueError):
        raise DatasetError(f"date must be YYYY-MM-DD, got {record['date']!r}") from None

    item = record["item"]
    if not isinstance(item, str) or not normalize_item(item):
        raise DatasetError(f"item must be a non-empty string, got {item!r}")

    try:
        source = Source(record["source"])
    except ValueError:
        raise DatasetError(f"source must be one of {[s.value for s in Source]}") from None

    meta = record.get("meta", {})
    if not isinstance(meta, dict):
        raise DatasetError("meta must be an object")

    owners = frozenset(name for name, flag in owners_by_person.items() if flag)
    members = frozenset(owners_by_person)
    return Observation(seq, date, item.strip(), owners, source, meta, members)


def _to_record(obs: Observation, people: Sequence[str]) -> dict[str, Any]:
    record: dict[str, Any] = {
        "seq": obs.seq,
        "date": obs.date.isoformat(),
        "item": obs.item,
        "owners": {
            name: name in obs.owners
            for name in people
            if obs.members is None or name in obs.members
        },
        "source": obs.source.value,
    }
    if obs.meta:
        record["meta"] = obs.meta

    return record
