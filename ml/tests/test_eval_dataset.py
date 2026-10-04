import datetime as dt
import json

import pytest

from divvy_ml.eval.dataset import (
    Dataset,
    DatasetError,
    Observation,
    Source,
    from_observations,
    from_records,
    load,
    normalize_item,
    parse_lines,
    save,
)
from divvy_ml.synthetic import generate
from divvy_ml.synthetic.__main__ import main
from divvy_ml.synthetic.personas import PEOPLE


def record(**overrides):
    base = {
        "seq": 1,
        "date": "2026-01-02",
        "item": "Coke Zero 12-pack",
        "owners": {"sumedh": False, "savar": False, "andrew": False, "tj": True},
        "source": "real",
    }
    base.update(overrides)
    return base


def lines(*records):
    return [json.dumps(r) for r in records]


def test_roundtrip_keeps_people_order_and_meta(tmp_path):
    path = tmp_path / "obs.jsonl"
    obs = Observation(
        1, dt.date(2026, 1, 2), "Paper towels", frozenset({"tj", "savar"}), Source.REAL, {"k": "v"}
    )
    dataset = Dataset(("sumedh", "savar", "andrew", "tj"), (obs,), Source.REAL)

    save(path, dataset)
    loaded = load(path)

    assert loaded == dataset
    assert loaded.people == ("sumedh", "savar", "andrew", "tj")
    assert loaded.observations[0].meta == {"k": "v"}


def test_sorted_by_date_then_seq():
    rows = lines(
        record(seq=3, date="2026-01-01"),
        record(seq=1, date="2026-01-05"),
        record(seq=2, date="2026-01-01"),
    )

    assert [o.seq for o in parse_lines(rows).observations] == [2, 3, 1]


def test_labels_follow_people_order():
    dataset = parse_lines(
        lines(record(owners={"sumedh": True, "savar": False, "andrew": True, "tj": True}))
    )

    assert dataset.labels().tolist() == [[1, 0, 1, 1]]


def test_any_household_size_is_supported():
    owners = {"ana": True, "ben": False, "cy": True}
    dataset = parse_lines(lines(record(owners=owners)))

    assert dataset.people == ("ana", "ben", "cy")
    assert dataset.labels().tolist() == [[1, 0, 1]]


def test_loads_real_data(tmp_path):
    path = tmp_path / "real.jsonl"
    path.write_text("\n".join(lines(record(seq=1), record(seq=2, item="Eggs"))), encoding="utf-8")

    dataset = load(path, expect=Source.REAL)

    assert dataset.source is Source.REAL
    assert len(dataset) == 2
    assert dataset.items == ["Coke Zero 12-pack", "Eggs"]


def test_loads_synthetic_data_written_by_the_generator(tmp_path):
    path = tmp_path / "synthetic.jsonl"
    main(["--size", "300", "--seed", "5", "--out", str(path)])

    dataset = load(path, expect=Source.SYNTHETIC)

    assert len(dataset) == 300
    assert dataset.people == PEOPLE
    assert dataset.source is Source.SYNTHETIC


def test_from_records_accepts_exported_rows():
    rows = [p.to_json() for p in generate(50, seed=2)]

    dataset = from_records(rows, expect=Source.SYNTHETIC)

    assert len(dataset) == 50
    assert dataset.labels().sum(axis=1).min() >= 1


def test_from_records_reports_row_numbers():
    with pytest.raises(DatasetError, match="line 2"):
        from_records([record(seq=1), record(seq=2, item="  ")])


def test_from_observations_orders_and_checks_the_household():
    late = Observation(2, dt.date(2026, 2, 1), "Eggs", frozenset({"tj"}), Source.REAL)
    early = Observation(1, dt.date(2026, 1, 1), "Milk", frozenset({"tj"}), Source.REAL)

    dataset = from_observations([late, early], ("sumedh", "tj"), Source.REAL)
    assert [o.seq for o in dataset.observations] == [1, 2]

    with pytest.raises(DatasetError, match="outside the household"):
        from_observations([early], ("sumedh",), Source.REAL)


@pytest.mark.parametrize(
    "bad",
    [
        record(owners={"sumedh": False, "savar": False, "andrew": False, "tj": False}),
        record(owners={"sumedh": 1, "savar": 0, "andrew": 0, "tj": 0}),
        record(owners={}),
        record(owners=["tj"]),
        record(item="   "),
        record(date="01/02/2026"),
        record(seq=0),
        record(seq=True),
        record(source="imaginary"),
        record(meta="text"),
        {"seq": 1},
        ["not", "an", "object"],
    ],
)
def test_rejects_invalid_records(bad):
    with pytest.raises(DatasetError, match="line 1"):
        parse_lines(lines(bad))


def test_rejects_duplicate_seq():
    with pytest.raises(DatasetError, match="duplicate seq"):
        parse_lines(lines(record(seq=1), record(seq=1)))


def test_rejects_mixed_sources():
    with pytest.raises(DatasetError, match="separate files"):
        parse_lines(lines(record(seq=1), record(seq=2, source="synthetic")))


def test_rejects_unexpected_source():
    with pytest.raises(DatasetError, match="expected only real"):
        parse_lines(lines(record(source="synthetic")), expect=Source.REAL)


def test_rejects_an_empty_dataset():
    with pytest.raises(DatasetError, match="no observations"):
        parse_lines([])


def test_reports_every_bad_line():
    rows = lines(record(seq=1, item=""), record(seq=2)) + ["{not json"]

    with pytest.raises(DatasetError) as err:
        parse_lines(rows)

    assert "line 1" in str(err.value)
    assert "line 3" in str(err.value)


def test_normalize_item_collapses_case_and_punctuation():
    assert normalize_item("Coke  Zero, 12-PACK!") == "coke zero 12 pack"
