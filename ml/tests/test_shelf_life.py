import pytest

from divvy_ml.shelf_life import CATEGORIES, SOURCES, category_for, estimate_for
from divvy_ml.shelf_life.mapping import _KEYWORDS


@pytest.mark.parametrize(
    ("description", "category"),
    [
        ("ORG WHOLE MILK 1GAL", "milk"),
        ("Large Eggs 12ct", "eggs"),
        ("Chicken Breast Boneless", "raw_poultry"),
        ("Ground Beef 80/20", "raw_ground_meat"),
        ("Sirloin Steak", "raw_red_meat"),
        ("Atlantic Salmon Fillet", "raw_fish"),
        ("Baby Spinach", "leafy_greens"),
        ("Strawberries 1lb", "berries"),
        ("Bananas", "bananas"),
        ("Roma Tomatoes", "tomatoes"),
        ("Cream Cheese", "soft_cheese"),
        ("Sharp Cheddar Cheese", "hard_cheese"),
        ("Greek Yogurt", "yogurt"),
        ("Orange Juice", "juice"),
        ("Sourdough Bread", "bread"),
        ("Yellow Onions 3lb", "potatoes_onions"),
        ("Bell Peppers", "cucumber_pepper"),
    ],
)
def test_perishables_map_to_a_category(description, category):
    assert category_for(description) == category


@pytest.mark.parametrize(
    "description",
    [
        "Frozen Chicken Nuggets",
        "Frozen Strawberries",
        "Almond Milk",
        "Peanut Butter",
        "Apple Sauce",
        "Canned Tomatoes",
        "Chicken Broth",
        "Ice Cream",
        "Spaghetti Pasta",
        "Basmati Rice",
        "Dish Soap",
        "Paper Towels",
        "Mystery Item",
        "",
    ],
)
def test_non_perishables_have_no_estimate(description):
    assert estimate_for(description) is None


def test_estimate_carries_the_range():
    estimate = estimate_for("whole milk")
    assert estimate is not None
    assert (estimate.min_days, estimate.max_days, estimate.storage) == (5, 7, "fridge")


def test_every_category_has_keywords_and_a_cited_source():
    assert set(_KEYWORDS) == set(CATEGORIES)
    for shelf_life in CATEGORIES.values():
        assert shelf_life.source < len(SOURCES)
