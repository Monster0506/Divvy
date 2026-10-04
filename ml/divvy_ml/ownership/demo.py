"""A built-in demo model, for showing the ownership guesses before a household has any history.

With DIVVY_DEMO_MODEL on, a household that has too little real history is answered by a model fitted
on a fixed catalog of everyday items and who usually owns them, spread over the request's members.
Nothing is stored and no receipts are made up. It stands in for the household's own model, which
takes over as soon as the household has enough confirmed items (see MIN_HISTORY). It is for demos.
Leave it off in production.
"""

import datetime as dt
import hashlib
import os
import threading
from datetime import UTC, datetime

from divvy_ml.embed import Embedder
from divvy_ml.eval.dataset import Dataset, Observation, Source
from divvy_ml.models import make_models
from divvy_ml.models.abstain import AbstentionPolicy
from divvy_ml.ownership.manager import DEFAULT_MODEL, ModelState

DEMO_ENV = "DIVVY_DEMO_MODEL"

# Every catalog item is seen this many times, and the newest copies cover them all.
REPEATS = 6

# More willing to answer than the real policy, so a near match to a catalog item is still guessed.
DEMO_POLICY = AbstentionPolicy(min_history=0, min_similarity=0.3)

Everyone = None

# Who usually owns what, as positions in the member list (wrapped to its size); None is everyone.
# Names are what receipt reading produces: lowercase, abbreviations expanded, sizes dropped.
CATALOG: list[tuple[tuple[int, ...] | None, list[str]]] = [
    (
        Everyone,
        [
            "milk",
            "whole milk",
            "2% milk",
            "great value 2% milk",
            "great value whole",
            "great value milk",
            "white bread",
            "bread",
            "wheat bread",
            "eggs",
            "eggs large",
            "dozen eggs",
            "butter",
            "great value water",
            "great value",
            "bottled water",
            "bananas",
            "banana",
            "apples",
            "oranges",
            "paper towel",
            "paper towels",
            "toilet paper",
            "great value plastic wrap",
            "plastic wrap",
            "aluminum foil",
            "trash bags",
            "dish soap",
            "laundry detergent",
            "sponges",
            "scrub daddy",
            "mainstays ice bin",
            "ice tray with lid",
            "ice bin",
            "ice tray",
            "coffee",
            "sugar",
            "flour",
            "olive oil",
            "salt",
            "pepper",
            "napkins",
            "paper plates",
            "zip bags",
            "light bulbs",
        ],
    ),
    (
        (0,),
        [
            "coke zero",
            "coca cola",
            "diet coke",
            "donuts",
            "doughnuts",
            "banana chocolate",
            "chocolate muffin",
            "boneless chicken breast",
            "chicken breast",
            "chicken thighs",
            "protein bar",
            "protein bars",
            "protein powder",
            "energy drink",
            "monster energy",
            "candy",
            "gummy bears",
            "cookies",
            "chocolate",
            "bacon",
            "sausage",
            "steak",
            "beef jerky",
        ],
    ),
    (
        (1,),
        [
            "paneer block",
            "paneer",
            "greek yogurt",
            "greek yogurt vanilla",
            "yogurt",
            "chocolate chip",
            "chocolate chip cookies",
            "basmati rice",
            "tikka masala sauce",
            "curry paste",
            "mango juice",
            "naan",
            "lentils",
            "chickpeas",
            "ghee",
            "turmeric",
            "cilantro",
            "ginger",
            "green chili",
            "spinach",
            "samosas",
        ],
    ),
    (
        (2,),
        [
            "oat milk",
            "almond milk",
            "avocado",
            "avocado hass",
            "sourdough loaf",
            "sourdough bread",
            "sparkling water lime",
            "sparkling water",
            "tofu",
            "tofu extra firm",
            "peanut butter",
            "peanut butter creamy",
            "hummus",
            "quinoa",
            "kale",
            "berries",
            "blueberries",
            "granola",
            "totes umbrella",
            "umbrella",
            "umbrellas",
            "kombucha",
        ],
    ),
    (
        (3,),
        [
            "cheddar cheese shredded",
            "shredded cheese",
            "tortilla flour",
            "tortillas",
            "salsa",
            "salsa medium",
            "tortilla chips",
            "ground beef",
            "hot sauce",
            "taco seasoning",
            "refried beans",
            "limes",
            "jalapenos",
            "sour cream",
            "magnetic mul",
            "magnetic hooks",
            "storage bins",
            "command strips",
        ],
    ),
    (
        (4,),
        [
            "frozen pizza",
            "pizza",
            "ice cream",
            "ice cream vanilla",
            "potato chips",
            "chips",
            "soda",
            "root beer",
            "pizza rolls",
            "frozen fries",
            "french fries",
            "hot pockets",
            "mac and cheese",
            "bed in a bag",
            "bedding",
            "pillow",
            "blanket",
            "microwave popcorn",
        ],
    ),
    (
        (5,),
        [
            "ramen noodles",
            "ramen",
            "kimchi",
            "soy sauce",
            "frozen dumplings",
            "dumplings",
            "jasmine rice",
            "green tea",
            "tea",
            "miso",
            "seaweed",
            "rice vinegar",
            "sesame oil",
            "bok choy",
            "edamame",
        ],
    ),
    ((0, 1), ["red wine", "wine", "white wine"]),
    ((2, 3), ["popcorn", "popcorn microwave", "nachos"]),
    ((0, 3), ["beer", "beer 6pk", "hard seltzer"]),
    ((1, 2), ["tomato sauce marinara", "pasta sauce", "spaghetti", "penne pasta", "pasta"]),
]


def demo_enabled() -> bool:
    return os.environ.get(DEMO_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


def catalog_dataset(people: list[str]) -> Dataset:
    """The catalog repeated over the given people, oldest first, owners picked by position."""
    items = [(name, _owners(slots, people)) for slots, names in CATALOG for name in names]
    start = dt.date(2026, 1, 1)
    observations = []
    for repeat in range(REPEATS):
        # a different fixed order each pass, so the newest rows (held to calibrate) cover every item
        stride = 7 + 2 * repeat
        order = sorted(range(len(items)), key=lambda i: (i * stride) % len(items))
        for index in order:
            name, owners = items[index]
            observations.append(
                Observation(
                    seq=len(observations) + 1,
                    date=start + dt.timedelta(days=len(observations) // 6),
                    item=name,
                    owners=owners,
                    source=Source.SYNTHETIC,
                )
            )
    return Dataset(tuple(people), tuple(observations), Source.SYNTHETIC)


def _owners(slots: tuple[int, ...] | None, people: list[str]) -> frozenset[str]:
    if slots is None:
        return frozenset(people)

    return frozenset(people[slot % len(people)] for slot in slots)


class DemoModels:
    """Fits the demo model for a set of people on first use and keeps it."""

    def __init__(self, embedder: Embedder, model_name: str = DEFAULT_MODEL) -> None:
        self._embedder = embedder
        self._model_name = model_name
        self._states: dict[tuple[str, ...], ModelState] = {}
        self._lock = threading.Lock()

    def state_for(self, people: list[str]) -> ModelState:
        key = tuple(people)
        with self._lock:
            state = self._states.get(key)
            if state is None:
                state = self._states[key] = self._build(people)

            return state

    def _build(self, people: list[str]) -> ModelState:
        dataset = catalog_dataset(people)
        model = make_models([self._model_name], self._embedder)[0]
        model.fit(dataset.items, dataset.labels(), dataset.known())
        digest = hashlib.sha1(",".join(people).encode(), usedforsecurity=False).hexdigest()[:8]
        return ModelState(
            version=f"demo-{self._model_name}-{len(dataset)}-{digest}",
            model=model,
            people=dataset.people,
            history_size=len(dataset),
            revision=0,
            trained_at=datetime.now(UTC).isoformat(timespec="seconds"),
            metrics={"demo": True, "calibrated": bool(getattr(model, "calibrated", False))},
        )
