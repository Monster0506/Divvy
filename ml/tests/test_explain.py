import pytest

from divvy_ml.recurrence.explain import explain_running_low


@pytest.mark.parametrize(
    ("interval", "since", "expected"),
    [
        (8, 9, "usually purchased every 8 days, last purchased 9 days ago"),
        (1, 1, "usually purchased every 1 day, last purchased 1 day ago"),
        (14, 21, "usually purchased every 2 weeks, last purchased 3 weeks ago"),
        (7, 7, "usually purchased every 7 days, last purchased 7 days ago"),
        (10, 15, "usually purchased every 10 days, last purchased 15 days ago"),
        (7.6, 0, "usually purchased every 8 days, last purchased today"),
        (0.2, 2, "usually purchased every 1 day, last purchased 2 days ago"),
    ],
)
def test_explanation_wording(interval, since, expected):
    assert explain_running_low(interval, since) == expected
