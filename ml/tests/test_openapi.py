from divvy_ml.openapi import OPENAPI_PATH, render


def test_committed_openapi_is_up_to_date():
    assert OPENAPI_PATH.exists(), "ml/openapi.json missing; run `make openapi`"
    assert OPENAPI_PATH.read_text() == render(), (
        "ml/openapi.json is out of date; run `make openapi` and commit the result"
    )
