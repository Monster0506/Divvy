import datetime as dt
import uuid
from contextlib import contextmanager

import pytest

from divvy_ml.eval import export
from divvy_ml.eval.dataset import DatasetError, Source, load
from divvy_ml.eval.split import check_no_leakage, chrono_split

ANA, BEN, CAL = (uuid.UUID(int=n) for n in (1, 2, 3))


def at(day, hour=12):
    return dt.datetime(2026, 1, day, hour, tzinfo=dt.UTC)


PERSONS = [
    (ANA, "Ana", at(1, 0), None),
    (BEN, "Ben", at(1, 0), at(20)),
    (CAL, "Cal", at(10, 0), None),
]


def row(item, day, description, owner, label="manual", confirmed=None):
    return (item, at(day), confirmed or at(day, 18), description, label, owner)


def test_one_observation_per_item_with_final_owners_and_original_date():
    rows = [
        row(1, 2, "Milk", ANA),
        row(1, 2, "Milk", BEN),
        row(2, 3, "Eggs", ANA, label="predicted_corrected", confirmed=at(15)),
    ]

    obs = export.build(PERSONS, rows)

    assert [(o.seq, o.item, sorted(o.owners)) for o in obs] == [
        (1, "Milk", ["Ana", "Ben"]),
        (2, "Eggs", ["Ana"]),
    ]
    assert obs[1].date == dt.date(2026, 1, 3)
    assert obs[1].meta == {"label_source": "predicted_corrected"}
    assert all(o.source is Source.REAL for o in obs)


def test_members_follow_joining_and_archiving():
    rows = [row(1, 2, "Milk", ANA), row(2, 12, "Rice", ANA), row(3, 25, "Tea", ANA)]

    early, middle, late = export.build(PERSONS, rows)

    assert early.members == {"Ana", "Ben"}
    assert middle.members == {"Ana", "Ben", "Cal"}
    assert late.members == {"Ana", "Cal"}


def test_duplicate_names_are_told_apart():
    twin = uuid.UUID(int=4)
    persons = [*PERSONS, (twin, "Ana", at(5, 0), None)]

    obs = export.build(persons, [row(1, 6, "Milk", twin)])

    assert obs[0].owners == {f"Ana ({str(twin)[:8]})"}


class FakeConn:
    def __init__(self, persons, owners):
        self.results = [persons, owners]
        self.queries = []

    @contextmanager
    def cursor(self):
        conn = self

        class Cursor:
            def execute(self, sql, params):
                conn.queries.append((sql, params))

            def fetchall(self):
                return conn.results[len(conn.queries) - 1]

        yield Cursor()


def test_export_writes_a_file_that_loads_and_has_no_leakage(tmp_path):
    rows = [row(n, n + 1, f"Item {n}", ANA if n % 2 else BEN) for n in range(1, 12)]
    out = tmp_path / "real.jsonl"
    conn = FakeConn(PERSONS, rows)

    count = export.export(conn, "house-1", out)

    dataset = load(out, expect=Source.REAL)
    assert count == len(dataset) == 11
    check_no_leakage(dataset.dates, chrono_split(dataset.dates))
    assert all(params == ("house-1",) for _, params in conn.queries)


def test_export_rejects_a_household_without_confirmed_items(tmp_path):
    with pytest.raises(DatasetError):
        export.export(FakeConn(PERSONS, []), "house-1", tmp_path / "x.jsonl")


def test_main_needs_a_database_url(monkeypatch, capsys):
    monkeypatch.delenv("DATABASE_URL", raising=False)

    assert export.main(["--household", "h"]) == 2
    assert "DATABASE_URL" in capsys.readouterr().err
