"""Turns a printed receipt date into a date, or None when it is not a known format."""

from datetime import date, datetime

_FORMATS = (
    "%m/%d/%y",
    "%m/%d/%Y",
    "%Y-%m-%d",
    "%m-%d-%y",
    "%m-%d-%Y",
    "%Y/%m/%d",
    "%B %d, %Y",
    "%b %d, %Y",
    "%B %d %Y",
    "%b %d %Y",
    "%d %B %Y",
    "%d %b %Y",
)


def normalise_date(raw: str | None) -> date | None:
    text = (raw or "").strip()
    if not text:
        return None
    for fmt in _FORMATS:
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    return None
