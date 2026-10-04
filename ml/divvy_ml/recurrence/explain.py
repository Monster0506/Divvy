"""Human-readable explanations for running-low suggestions."""


def _duration(days: int) -> str:
    if days >= 14 and days % 7 == 0:
        return f"{days // 7} weeks"
    if days == 1:
        return "1 day"
    return f"{days} days"


def explain_running_low(interval_days: float, days_since_last: int) -> str:
    """e.g. "usually purchased every 8 days, last purchased 9 days ago"."""
    usual = _duration(max(1, round(interval_days)))
    if days_since_last <= 0:
        last = "last purchased today"
    else:
        last = f"last purchased {_duration(days_since_last)} ago"
    return f"usually purchased every {usual}, {last}"
