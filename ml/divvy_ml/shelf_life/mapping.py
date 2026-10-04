"""Maps an item description to a perishable category, or to no estimate for non-perishables."""

from divvy_ml.shelf_life.table import CATEGORIES, ShelfLife
from divvy_ml.text import normalize_item

# Phrases that mark a shelf-stable, frozen or non-food product. Any of them rules out an estimate.
_NON_PERISHABLE = (
    "frozen", "canned", "can of", "dried", "dry", "shelf stable", "powder", "jarred", "pickled",
    "almond milk", "oat milk", "soy milk", "coconut milk", "rice milk", "evaporated milk",
    "condensed milk", "peanut butter", "almond butter", "cookie butter", "ice cream",
    "cream of tartar", "egg noodle", "pasta", "rice", "cereal", "flour", "sugar", "oil",
    "vinegar", "sauce", "dressing", "broth", "stock", "soup", "bouillon", "chips", "cracker",
    "cookie", "candy", "chocolate", "coffee", "tea", "soda", "water", "beans", "lentil", "nuts",
    "jam", "jelly", "honey", "salt", "spice", "snack", "soap", "detergent", "paper", "foil",
    "shampoo", "toothpaste",
)

_KEYWORDS: dict[str, tuple[str, ...]] = {
    "milk": ("milk", "whole milk", "skim milk", "half and half"),
    "cream": ("cream", "heavy cream", "whipping cream", "sour cream"),
    "yogurt": ("yogurt", "yoghurt", "kefir"),
    "soft_cheese": (
        "cream cheese", "cottage cheese", "ricotta", "mozzarella", "brie", "feta", "goat cheese",
    ),
    "hard_cheese": ("cheese", "cheddar", "parmesan", "swiss", "gouda", "provolone"),
    "eggs": ("egg",),
    "butter": ("butter",),
    "raw_poultry": ("chicken", "turkey", "drumstick"),
    "raw_ground_meat": ("ground beef", "ground turkey", "ground pork", "ground chicken", "mince"),
    "raw_red_meat": ("steak", "beef", "pork", "roast", "lamb", "rib", "sausage"),
    "raw_fish": ("salmon", "cod", "tilapia", "shrimp", "fish", "scallop"),
    "deli_meat": ("deli", "ham", "salami", "lunch meat", "prosciutto"),
    "bacon": ("bacon",),
    "hot_dogs": ("hot dog", "frankfurter"),
    "tofu": ("tofu", "tempeh"),
    "hummus": ("hummus", "guacamole"),
    "juice": ("juice", "orange juice", "apple juice"),
    "leafy_greens": ("lettuce", "spinach", "kale", "arugula", "salad", "romaine", "cabbage"),
    "berries": ("strawberry", "blueberry", "raspberry", "blackberry", "grape"),
    "fresh_herbs": ("cilantro", "parsley", "basil", "mint", "dill", "chive", "fresh herbs"),
    "mushrooms": ("mushroom",),
    "broccoli": ("broccoli", "cauliflower", "asparagus", "green beans"),
    "cucumber_pepper": ("cucumber", "bell pepper", "jalapeno", "zucchini", "squash", "celery"),
    "carrots": ("carrot", "beet", "radish"),
    "apples": ("apple", "pear"),
    "citrus": ("orange", "lemon", "lime", "grapefruit", "clementine"),
    "bananas": ("banana",),
    "tomatoes": ("tomato", "peach", "plum", "nectarine"),
    "avocado": ("avocado",),
    "bread": ("bread", "bagel", "tortilla", "bun", "roll", "baguette", "pita"),
    "potatoes_onions": ("potato", "onion", "garlic", "shallot"),
}


def _singular(token: str) -> str:
    if token.endswith("ies") and len(token) > 4:
        return token[:-3] + "y"
    if token.endswith("oes") and len(token) > 4:
        return token[:-2]
    if token.endswith("s") and not token.endswith("ss") and len(token) > 3:
        return token[:-1]
    return token


def _tokens(text: str) -> tuple[str, ...]:
    return tuple(_singular(t) for t in normalize_item(text).split())


def _contains(tokens: tuple[str, ...], phrase: tuple[str, ...]) -> bool:
    n = len(phrase)
    return any(tokens[i : i + n] == phrase for i in range(len(tokens) - n + 1))


_NON_PERISHABLE_PHRASES = tuple(_tokens(p) for p in _NON_PERISHABLE)

# Longest phrase first so specific beats general.
_PHRASES: tuple[tuple[tuple[str, ...], str], ...] = tuple(
    sorted(
        [(_tokens(p), cat) for cat, phrases in _KEYWORDS.items() for p in phrases],
        key=lambda e: (-len(e[0]), -sum(len(t) for t in e[0])),
    )
)


def category_for(description: str) -> str | None:
    """The perishable category of an item, or None for non-perishables and unknown items."""
    tokens = _tokens(description)
    if any(_contains(tokens, phrase) for phrase in _NON_PERISHABLE_PHRASES):
        return None
    return next((cat for phrase, cat in _PHRASES if _contains(tokens, phrase)), None)


def estimate_for(description: str) -> ShelfLife | None:
    """The shelf-life estimate for an item, or None when there is no estimate to give."""
    category = category_for(description)
    return CATEGORIES[category] if category else None
