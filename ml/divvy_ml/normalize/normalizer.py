"""Turn a raw receipt line description into a readable, comparable product name.

Rule based only: lowercase, drop codes and sizes, expand abbreviations. Tokens that are not in
the dictionary are kept as they are, so an unknown abbreviation degrades to its lowercase form
rather than being guessed at.
"""

import re

from divvy_ml.normalize.abbreviations import ABBREVIATIONS

_UNITS = r"fl\s*oz|oz|fl|lb|lbs|kg|g|gm|mg|ml|l|ltr|gal|qt|pt|ct|pk|pack|ea|each|doz"
# Sizes and weights such as 16oz, 1.5 l, 12ct, 2 lb, @ 1.99/lb
_SIZE = re.compile(rf"\b\d+(?:\.\d+)?\s*(?:{_UNITS})\b")
_PER_UNIT_PRICE = re.compile(r"@\s*\d+(?:\.\d+)?(?:/\w+)?")
_PRICE = re.compile(r"\$?\d+\.\d{2}")
# Product and PLU codes
_CODE = re.compile(r"^\d{3,}$")
_SPLIT = re.compile(r"[^a-z0-9%]+")
# A unit left on its own at the end ("GV WHOLE GAL") is a size with its number lost
_TRAILING_UNITS = frozenset({"gal", "lb", "lbs", "oz", "ct", "pk", "ea", "doz"})


def normalize_product_name(raw: str) -> str:
    """Return the readable lowercase name for a raw receipt description.

    Returns an empty string if nothing but codes and sizes is left.
    """
    text = _PER_UNIT_PRICE.sub(" ", raw.lower())
    text = _SIZE.sub(" ", text)
    text = _PRICE.sub(" ", text)
    out: list[str] = []
    for token in _SPLIT.split(text):
        if not token or _CODE.match(token):
            continue
        for word in ABBREVIATIONS.get(token, token).split():
            if not out or out[-1] != word:
                out.append(word)
    while out and out[-1] in _TRAILING_UNITS:
        out.pop()
    return " ".join(out)
