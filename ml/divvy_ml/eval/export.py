"""Export a household's confirmed receipts from Postgres as an evaluation dataset.

python -m divvy_ml.eval.export --household <uuid> --out data/real/observations.jsonl

Needs `pip install -e ".[export]"` and DATABASE_URL (or --database-url).
"""

import argparse
import datetime as dt
import os
import sys
from collections import defaultdict
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from divvy_ml.eval.dataset import DatasetError, Observation, Source, from_observations, save

PERSON_SQL = """
SELECT id, display_name, created_at, archived_at
FROM person
WHERE household_id = %s
ORDER BY created_at, id
"""

OWNER_SQL = """
SELECT i.id, r.purchased_at, r.confirmed_at, i.description, i.label_source, o.person_id
FROM receipt r
JOIN receipt_item i ON i.household_id = r.household_id AND i.receipt_id = r.id
JOIN item_owner o ON o.household_id = i.household_id AND o.receipt_item_id = i.id
WHERE r.household_id = %s AND r.status = 'confirmed'
ORDER BY r.purchased_at, r.created_at, r.id, i.line_number, o.person_id
"""


def fetch(conn: Any, household_id: str) -> tuple[list[tuple], list[tuple]]:
    with conn.cursor() as cur:
        cur.execute(PERSON_SQL, (household_id,))
        persons = cur.fetchall()
        cur.execute(OWNER_SQL, (household_id,))
        owners = cur.fetchall()

    return persons, owners


def _names(persons: Sequence[tuple]) -> dict[Any, str]:
    """Display names, with a short id added where two people share one."""
    counts: dict[str, int] = defaultdict(int)
    for _, name, _, _ in persons:
        counts[name] += 1

    return {
        pid: name if counts[name] == 1 else f"{name} ({str(pid)[:8]})"
        for pid, name, _, _ in persons
    }


def build(persons: Sequence[tuple], owner_rows: Sequence[tuple]) -> list[Observation]:
    """One observation per item, in the order the rows arrive (purchase date, then line)."""
    names = _names(persons)
    items: dict[Any, dict[str, Any]] = {}
    for item_id, purchased_at, confirmed_at, description, label_source, person_id in owner_rows:
        date = purchased_at.date() if isinstance(purchased_at, dt.datetime) else purchased_at
        entry = items.setdefault(
            item_id,
            {
                "date": date,
                "at": confirmed_at,
                "item": description,
                "label": label_source,
                "owners": set(),
            },
        )
        entry["owners"].add(person_id)

    observations = []
    for seq, entry in enumerate(items.values(), start=1):
        at = entry["at"]
        members = {
            pid
            for pid, _, created, archived in persons
            if created <= at and (archived is None or archived > at)
        } | entry["owners"]
        meta = {"label_source": entry["label"]} if entry["label"] else {}
        observations.append(
            Observation(
                seq,
                entry["date"],
                entry["item"],
                frozenset(names[p] for p in entry["owners"]),
                Source.REAL,
                meta,
                frozenset(names[p] for p in members),
            )
        )

    return observations


def export(conn: Any, household_id: str, out: Path) -> int:
    persons, owner_rows = fetch(conn, household_id)
    observations = build(persons, owner_rows)
    if not observations:
        raise DatasetError(f"household {household_id} has no confirmed, owned items")

    people = list(_names(persons).values())
    save(out, from_observations(observations, people, Source.REAL))
    return len(observations)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m divvy_ml.eval.export", description=__doc__)
    parser.add_argument("--household", required=True, help="household uuid")
    parser.add_argument("--out", type=Path, default=Path("observations.jsonl"))
    parser.add_argument("--database-url", default=os.environ.get("DATABASE_URL"))
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.database_url:
        print("set DATABASE_URL or pass --database-url", file=sys.stderr)
        return 2

    try:
        import psycopg
    except ImportError:
        print('psycopg is missing: pip install -e ".[export]"', file=sys.stderr)
        return 2

    try:
        with psycopg.connect(args.database_url) as conn:
            count = export(conn, args.household, args.out)
    except DatasetError as err:
        print(err, file=sys.stderr)
        return 1

    print(f"wrote {count} observations to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
