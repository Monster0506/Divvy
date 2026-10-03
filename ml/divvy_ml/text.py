"""Text helpers shared by the data and model code."""

import re

_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def normalize_item(text: str) -> str:
    """Key used to decide whether two descriptions are the same item."""
    return _NON_ALNUM.sub(" ", text.lower()).strip()
