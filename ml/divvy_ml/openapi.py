"""Export the service's OpenAPI contract to ml/openapi.json.

Run with `python -m divvy_ml.openapi`. A test fails when the committed file
drifts from the app, so re-run this after changing any endpoint or schema.
"""

import json
from pathlib import Path

from divvy_ml.app import app

OPENAPI_PATH = Path(__file__).resolve().parent.parent / "openapi.json"


def render() -> str:
    return json.dumps(app.openapi(), indent=2, sort_keys=True) + "\n"


def main() -> None:
    OPENAPI_PATH.write_text(render())
    print(f"wrote {OPENAPI_PATH}")


if __name__ == "__main__":
    main()
