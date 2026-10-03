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

## Evaluation metrics

`divvy_ml.eval` scores ownership predictions. Arrays have one row per item and one column per person.

- `report(truth, probs, people)`: exact match, per-person precision/recall/F1, Hamming loss, Brier, log loss, and calibration
  (`ece` over every item/person decision, `item_ece` for the confidence that the whole predicted set is right).
- `reliability_bins`, `expected_calibration_error`, `reliability_svg`: the reliability diagram data and a plain SVG.
- `coverage_curve`, `operating_point`, `pick_threshold`: accuracy when only the most confident items are auto-assigned,
  and the lowest threshold that reaches a target accuracy. Pick it on validation data, then apply it to test data.

## Datasets and splits

Labelled observations are JSONL, one purchase per line: `seq`, `date`, `item`, `owners` (person -> true/false, at least one true),
`source` (`real` or `synthetic`) and optional `meta`. This is the format `python -m divvy_ml.synthetic` writes, and the format a
database export should produce. A file holds one source; mixed files are rejected. `owners` lists the people in the
household at that time. Someone missing from a line (not yet joined, or archived) is unknown, never a "no".

- `load(path, expect=Source.REAL)` / `from_records(rows)`: validate and return a `Dataset` in date-then-seq order, with `labels()` as a 0/1 matrix.
- `chrono_split(dates)` and `walk_forward(dates, start, stop, step)`: time-ordered boundaries that never put one purchase date on both sides,
  because items bought on one trip belong together.
- `check_no_leakage(dates, split)` raises unless every later period is strictly after every earlier one.

## Evaluation report

```
python -m divvy_ml.eval --household synthetic --out eval-report
python -m divvy_ml.eval --data data/real/observations.jsonl --expect real
```

Walks forward through the household one block of days at a time, refitting each model on earlier days only, and scores
the held-out test period. `eval-report/` gets `report.md`, `report.html` (with the reliability diagram) and `results.json`.
The report has exact match, per-person precision/recall, calibration (ECE), cold-start slices, the auto-assign operating
point, the coverage-vs-accuracy curve and a recommended confidence threshold. The threshold is picked on validation data
for `--target` accuracy (default 0.95) and applied unchanged to test. `--min-exact` makes the command fail when the best
model scores below it, which is how CI catches regressions. CI runs it on the synthetic household and uploads the report.

Models are registered in `divvy_ml/models/registry.py`; `--models frequency exact-item` picks some.

## Embeddings

`POST /embed` takes `{"texts": [...]}` (1 to 256 descriptions, up to 512 characters each) and returns one L2-normalised
vector per text, so a dot product is the cosine similarity. The embedder is loaded once at startup and chosen with
`DIVVY_EMBEDDER`: `hashing` (default), `minilm`, or `st:<sentence-transformers model id>`. The semantic models need
`pip install -e ".[semantic]"`; the Docker image uses the default and has no model download.

Choice: **`hashing` by default, `all-MiniLM-L6-v2` (`minilm`) when semantic similarity matters.** Measured with
`python -m divvy_ml.embed.compare` on CPU. AUC is the chance a different wording of the same product scores
as more similar than a different product, over the synthetic catalog.

| model | dims | 30 items | AUC | related / unrelated similarity |
|---|---|---|---|---|
| hashing | 4096 | 1 ms | 0.896 | 0.452 / 0.063 |
| all-MiniLM-L6-v2 | 384 | 38 ms | 0.953 | 0.597 / 0.186 |
| paraphrase-MiniLM-L3-v2 | 384 | 116 ms | 0.944 | 0.580 / 0.131 |
| bge-small-en-v1.5 | 384 | 47 ms | 0.957 | 0.764 / 0.556 |

- MiniLM-L6 has 22.7M parameters (91 MB) and uses about 563 MB resident including PyTorch, under the 1 GB budget.
- bge-small separates slightly better but puts everything close together (unrelated pairs average 0.556), which
  flattens the sharpened neighbour weighting the ownership models use. MiniLM-L6 keeps a wider gap.
- Hashing is lexical, so it links "Coke Zero 24-pack" to "Coke Zero 12-pack" but not to "Coca-Cola Zero Sugar".
  It needs no download and cannot leak, which makes it the safe default.
- Every option embeds 30 items in well under the 2 second budget.
