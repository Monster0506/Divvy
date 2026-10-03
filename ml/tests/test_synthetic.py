import json
from collections import Counter, defaultdict

import pytest

from divvy_ml.synthetic import DEFAULT_SIZE, Purchase, bayes_ceiling, generate, is_generated
from divvy_ml.synthetic.__main__ import main
from divvy_ml.synthetic.personas import DIETS, HABITS, PEOPLE, PRODUCTS, Habit, check
from divvy_ml.synthetic.simulate import DAYS_PER_WEEK, HABIT, PRODUCT, owner_distribution


@pytest.fixture(scope="module")
def household() -> list[Purchase]:
    return generate()


def test_same_seed_reproduces_exactly():
    first = generate(500, seed=3)
    second = generate(500, seed=3)

    assert [p.to_json() for p in first] == [p.to_json() for p in second]


def test_different_seed_differs():
    assert generate(500, seed=3) != generate(500, seed=4)


def test_default_household_is_large_and_ordered(household):
    keys = [(p.date, p.seq) for p in household]

    assert len(household) == DEFAULT_SIZE
    assert keys == sorted(keys)
    assert [p.seq for p in household] == list(range(1, DEFAULT_SIZE + 1))
    assert is_generated(household)


def test_spans_at_least_six_months(household):
    assert (household[-1].date - household[0].date).days >= 183


def test_every_purchase_has_at_least_one_owner_from_the_household(household):
    assert all(p.owners and p.owners <= set(PEOPLE) for p in household)


def test_diets_hold_even_for_deviations(household):
    for purchase in household:
        tags = PRODUCTS[purchase.meta[PRODUCT]].tags
        assert not any(DIETS[name] & tags for name in purchase.owners), purchase


def test_andrew_buys_two_loaves_a_week(household):
    weeks = (household[-1].date - household[0].date).days / DAYS_PER_WEEK
    loaves = sum(p.meta[HABIT] == "andrew:white_bread" for p in household)

    assert loaves / weeks == pytest.approx(2.0, rel=0.15)


def test_every_pair_and_trio_shares_something(household):
    combos = Counter(p.owners for p in household if 1 < len(p.owners) < len(PEOPLE))

    assert len(combos) == 10
    assert min(combos.values()) >= 50


def test_items_are_not_a_lookup_table(household):
    owners_by_item = defaultdict(set)
    for purchase in household:
        owners_by_item[purchase.item].add(purchase.owners)

    ambiguous = [item for item, sets in owners_by_item.items() if len(sets) > 1]
    assert len(ambiguous) > len(owners_by_item) / 3


@pytest.mark.parametrize(
    "habit", [h for h in HABITS if h.intro > 0 or h.until < 1], ids=lambda h: h.label
)
def test_habits_start_and_stop_on_schedule(household, habit):
    trip_start = {}
    for i, purchase in enumerate(household):
        trip_start.setdefault(purchase.date, i)

    progress = [
        trip_start[p.date] / len(household) for p in household if p.meta[HABIT] == habit.label
    ]
    assert all(habit.intro <= p < habit.until for p in progress)


def test_deviation_never_adds_forbidden_people():
    dist = owner_distribution(frozenset({"andrew", "tj"}), ("andrew", "tj"))

    assert sum(dist.values()) == pytest.approx(1.0)
    assert all(p == 0 for owners, p in dist.items() if owners - {"andrew", "tj"})


def test_catalog_check_rejects_diet_violations():
    with pytest.raises(ValueError, match="diet forbids"):
        check((Habit("ground_beef", "sumedh+tj", 1.0),), PRODUCTS)


def test_bayes_ceiling_is_below_perfect(household):
    assert 0.5 < bayes_ceiling(household, start=len(household) - 500) < 1.0


def test_json_record_shape(household):
    record = household[0].to_json()

    assert set(record) == {"seq", "date", "item", "owners", "source", "meta"}
    assert set(record["owners"]) == set(PEOPLE)
    assert any(record["owners"].values())
    assert record["source"] == "synthetic"


def test_command_writes_one_json_line_per_purchase(tmp_path, capsys):
    out = tmp_path / "nested" / "household.jsonl"

    main(["--size", "200", "--seed", "5", "--out", str(out)])

    lines = out.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 200
    assert json.loads(lines[0])["seq"] == 1
    assert "wrote 200 purchases" in capsys.readouterr().out


def test_command_rejects_empty_size(tmp_path):
    with pytest.raises(SystemExit):
        main(["--size", "0", "--out", str(tmp_path / "x.jsonl")])
