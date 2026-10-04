"""Conservative shelf-life ranges for perishable categories.

Ranges are days from purchase, taking the shorter end of the published guidance so a "use soon"
hint is never later than the safe window. Meat, fish and dairy are refrigerated and unopened;
produce and bread are kept the way people usually keep them (see `storage`).
"""

from dataclasses import dataclass

SOURCES = (
    "USDA FSIS FoodKeeper data, https://www.fsis.usda.gov/food-safety/safe-food-handling-and-preparation/food-safety-basics/foodkeeper-app",
    "USDA FSIS, Shell Eggs from Farm to Table, https://www.fsis.usda.gov/food-safety/safe-food-handling-and-preparation/eggs/shell-eggs-farm-table",
    "FoodSafety.gov, Cold Food Storage Chart, https://www.foodsafety.gov/food-safety-charts/cold-food-storage-charts",
)


@dataclass(frozen=True)
class ShelfLife:
    category: str
    min_days: int
    max_days: int
    storage: str
    # Index into SOURCES.
    source: int

    def __post_init__(self) -> None:
        if not 0 < self.min_days <= self.max_days:
            raise ValueError(f"invalid range for {self.category}")


def _fridge(category: str, lo: int, hi: int, source: int = 0) -> ShelfLife:
    return ShelfLife(category, lo, hi, "fridge", source)


def _counter(category: str, lo: int, hi: int, source: int = 0) -> ShelfLife:
    return ShelfLife(category, lo, hi, "counter", source)


CATEGORIES: dict[str, ShelfLife] = {
    c.category: c
    for c in (
        _fridge("milk", 5, 7, 2),
        _fridge("cream", 5, 10),
        _fridge("yogurt", 7, 14),
        _fridge("soft_cheese", 7, 14),
        _fridge("hard_cheese", 21, 42),
        _fridge("eggs", 21, 35, 1),
        _fridge("butter", 30, 60),
        _fridge("raw_poultry", 1, 2, 2),
        _fridge("raw_ground_meat", 1, 2, 2),
        _fridge("raw_red_meat", 3, 5, 2),
        _fridge("raw_fish", 1, 2, 2),
        _fridge("deli_meat", 3, 5, 2),
        _fridge("bacon", 7, 7, 2),
        _fridge("hot_dogs", 14, 14, 2),
        _fridge("tofu", 5, 7),
        _fridge("hummus", 5, 7),
        _fridge("juice", 7, 10),
        _fridge("leafy_greens", 3, 7),
        _fridge("berries", 3, 7),
        _fridge("fresh_herbs", 5, 7),
        _fridge("mushrooms", 5, 7),
        _fridge("broccoli", 5, 7),
        _fridge("cucumber_pepper", 5, 7),
        _fridge("carrots", 21, 28),
        _fridge("apples", 21, 28),
        _fridge("citrus", 14, 21),
        _counter("bananas", 2, 5),
        _counter("tomatoes", 3, 5),
        _counter("avocado", 3, 5),
        _counter("bread", 3, 5),
        _counter("potatoes_onions", 21, 35),
    )
}
