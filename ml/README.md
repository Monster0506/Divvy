# Divvy ML service

FastAPI service for receipt extraction and ownership prediction. Requires Python 3.12.

```
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
make install
make run     # uvicorn divvy_ml.app:app on :8000
make test    # pytest
make lint    # ruff check
make openapi # regenerate openapi.json
make docker  # build the divvy-ml image
```

Without make, run the commands from the Makefile directly.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness check |
| POST | `/receipts/extract` | Extract a structured receipt from an image (currently returns a fixed sample) |
| POST | `/ownership/predict` | Predict which household members own each item (currently returns `unknown` for every item) |

`POST /receipts/extract` takes exactly one of `image_id` (an image already in receipt storage) or `image_base64`, and returns an `ExtractedReceipt` (see `divvy_ml/receipts/schemas.py`). All money fields are integers in cents. Interactive docs are at `/docs` while the service is running.

`POST /ownership/predict` takes a `household_id`, the household's `people` and the `items` to classify, and returns one prediction per item with its owners, a confidence between 0 and 1, and alternative owner sets (see `divvy_ml/ownership/schemas.py`). The `receipts` and `ownership` modules must not import each other; a test enforces this.

## API contract

`openapi.json` is the committed contract the Kotlin backend builds against. After changing any endpoint or schema, run `make openapi` (`python -m divvy_ml.openapi`) and commit the result. `tests/test_openapi.py` fails when the file is out of date.

## Docker

```
docker build -t divvy-ml ml/
docker run --rm -p 8000:8000 divvy-ml
curl localhost:8000/health
```

The image is based on `python:3.12-slim`, runs as the non-root user `divvy` (uid 10001), serves on port 8000, and has a healthcheck that calls `/health`.

## CI

`.github/workflows/ml.yml` runs on pull requests and pushes to `main` that touch `ml/`. It runs `ruff check`, `ruff format --check` and `pytest` on Python 3.12, and builds the Docker image and checks that it serves `/health`. Run the same checks locally with `make lint test`.

## Synthetic household

A seeded generator for a four-person household, used to test models without real data. It simulates
about 3 years of shopping trips by default and is reproducible: the same seed gives the same data.

```
python -m divvy_ml.synthetic --size 6000 --seed 7 --out data/synthetic/household.jsonl
make synth
```

Each line is one purchase: `seq`, `date`, `item`, `owners` (every person true/false, at least one true),
`source` ("synthetic") and `meta` (product, category and the habit that produced it). The command also
prints the exact-set accuracy ceiling for the last 500 purchases: the best any model can do from the text alone.

The preferences are invented. Use it to check pipelines, not to pretrain a real household model.
