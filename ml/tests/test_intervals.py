from datetime import date, timedelta

from divvy_ml.recurrence.intervals import interval_stats

START = date(2026, 1, 1)


def test_eight_day_product_reports_about_eight_days():
    gaps = [8, 7, 9, 8, 8]
    days = [START]
    for gap in gaps:
        days.append(days[-1] + timedelta(days=gap))
    stats = interval_stats(("milk", d) for d in days)["milk"]
    assert stats.purchase_count == 6
    assert abs(stats.median_interval_days - 8) <= 2
    assert stats.last_purchase == days[-1]
    assert stats.interval_mad_days == 0


def test_same_day_duplicates_count_once():
    purchases = [("eggs", START), ("eggs", START), ("eggs", START + timedelta(days=5))]
    stats = interval_stats(purchases)["eggs"]
    assert stats.purchase_count == 2
    assert stats.median_interval_days == 5


def test_single_purchase_has_no_interval():
    stats = interval_stats([("rice", START)])["rice"]
    assert stats.median_interval_days is None
    assert stats.interval_mad_days is None


def test_unordered_input_and_variability():
    offsets = [20, 0, 10, 12, 30]
    purchases = [("tea", START + timedelta(days=o)) for o in offsets]
    stats = interval_stats(purchases)["tea"]
    assert stats.last_purchase == START + timedelta(days=30)
    assert stats.median_interval_days == 9
    assert stats.interval_mad_days == 1


def test_products_are_separate():
    purchases = [("a", START), ("b", START), ("a", START + timedelta(days=3))]
    stats = interval_stats(purchases)
    assert stats["a"].purchase_count == 2
    assert stats["b"].purchase_count == 1
