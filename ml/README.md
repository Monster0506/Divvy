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
| POST | `/receipts/extract` | Extract a structured receipt from an image with a Qwen vision model |
| POST | `/ownership/predict` | Predict which household members own each item, from the household's stored history |
| POST | `/embed` | Batched text embeddings |
| PUT / DELETE / GET | `/ownership/households/{id}/...` | Push, remove and inspect a household's history (see Household history) |

`POST /receipts/extract` takes exactly one of `image_id` (an image already in receipt storage) or `image_base64`, and returns an `ExtractedReceipt` (see `divvy_ml/receipts/schemas.py`). All money fields are integers in cents. Interactive docs are at `/docs` while the service is running.

`POST /ownership/predict` takes a `household_id`, the household's `people` and the `items` to classify, and returns one prediction per item with its owners, a confidence between 0 and 1, and alternative owner sets (see `divvy_ml/ownership/schemas.py`). The `receipts` and `ownership` modules must not import each other; a test enforces this.

## Receipt extraction

`POST /receipts/extract` sends the image to a Qwen vision model (OpenAI-compatible chat API) with a strict JSON schema
and a transcribe-only prompt, then converts the answer to the contract: dollars to integer cents, the printed date to
`purchased_on` (null when not recognised), and arithmetic checks as `warnings` (line total, items against subtotal,
subtotal plus tax against total; one cent of rounding is allowed). Values are never changed to make them agree.

| Variable | Default | Purpose |
|----------|---------|---------|
| `QWEN_API_KEY` | none | API key. Without a key the endpoint answers 503 |
| `QWEN_API_KEY_FILE` | none | Path of a file holding only the key, used when `QWEN_API_KEY` is empty |
| `QWEN_BASE_URL` | `https://maas.qwencloudapi.com/compatible-mode/v1` | Chat completions base |
| `QWEN_MODEL` | `qwen3.7-plus` | Model name |
| `QWEN_ENABLE_THINKING` / `QWEN_THINKING_BUDGET` | `true` / `512` | Thinking toggle and token budget |
| `QWEN_TIMEOUT_SECONDS` | `60` | Per-call timeout |
| `DIVVY_IMAGE_DIR` | none | Directory of stored receipt images (shared with the backend), needed for `image_id` |

Errors: 422 for a missing, oversized (over 10 MB), unreadable or non-JPEG/PNG/WebP image, 502 when the vision service
fails or returns something that is not a receipt, 503 when no key is set. Tests use a faked HTTP transport and the
receipt-bot fixtures in `tests/data/qwen/`, so they need no network. To try it for real, set `QWEN_API_KEY_FILE` and run
`pytest tests/test_receipts_live.py` with `DIVVY_LIVE_RECEIPT=<image path>`.

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

Export a household's confirmed items from Postgres (needs `pip install -e ".[export]"`):

```
DATABASE_URL=postgresql://... python -m divvy_ml.eval.export --household <uuid> --out data/real/observations.jsonl
```

Each item appears once with its final owners and its original purchase date. People who had not joined yet, or were
already archived, when the receipt was confirmed are left out of `owners`. `meta.label_source` keeps how the owners were set.

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

## Ownership models

Each model learns from one household's history and gives, per person, the probability they own an item. They share
`fit(items, labels, known)` and `predict(items)`, are listed in `divvy_ml/models/registry.py`, and never see another household.
`known` marks where a person was in the household, so someone who joined late is unknown before that, not a "no".

| model | what it uses |
|---|---|
| `frequency` | how often each person owns anything, ignoring the item |
| `exact-item` | past owners of the same normalised description, shrunk toward the prior |
| `knn` | owners of the 10 most similar past purchases, weighted by similarity^4, falling back to the prior |
| `logreg` | one logistic regression per person on the item embedding |
| `combined` | embedding plus kNN vote features; training rows get held-out votes so a row never votes for itself |

Test exact-set accuracy on the synthetic household (hashing embedder, walk-forward, ceiling 93.4%):

| model | 1,500 purchases | 6,000 purchases |
|---|---|---|
| frequency | 19.4 | 16.8 |
| exact-item | 84.9 | 89.4 |
| knn | 87.7 | **91.4** |
| logreg | 86.3 | 89.6 |
| combined | **89.4** | 90.7 |

`combined` is best while history is short and is the only model whose confidence is well calibrated (item ECE 0.070 at
1,500 and 0.022 at 6,000, against 0.24 and 0.16 for `knn`), so it is the default for auto-assigning. All of them beat
`frequency` by a wide margin. Use `--embedder minilm` to compare the semantic embedder.

## Calibration and unknown

The raw confidence assumes people are independent, which is only roughly true: `knn` says 0.9 when it is right about
75% of the time. `knn-calibrated` and `combined-calibrated` wrap a model with a calibrator (isotonic by default, or
`PlattCalibrator`). On every fit the newest 30% of history is held back, the model is fit on the rest, and its
predictions on the held-back rows teach the calibrator before the model is refit on everything. Predictions do not
change; only the confidence does, so an auto-assign threshold means what it says. With under 40 held-back rows the raw
confidence is kept.

Item-level ECE on the synthetic household (test period, lower is better):

| model | 1,500 purchases | 3,000 purchases |
|---|---|---|
| knn | 0.242 | 0.199 |
| knn-calibrated | **0.022** | **0.020** |
| combined | 0.070 | 0.042 |
| combined-calibrated | **0.031** | **0.021** |

At the threshold picked on validation for 95% accuracy, the calibrated models auto-assign 82 to 84% of items at 95.5 to
97.9% exact-set accuracy on test.

`AbstentionPolicy` answers "unknown" instead of guessing when the household has under 30 labelled items or the closest
past item is under 0.4 similar (hashing embedder; recalibrate for another). On the synthetic household, items below 0.3
similarity were never right and 0.3 to 0.5 were right about one time in five. The report shows how many items would be
unknown and how accurate the model is on each side. An empty household is always unknown.

## Household history

The service keeps a copy of each household's confirmed items so it can predict from them (see
`docs/adr/0002-ml-household-history.md`). The backend pushes changes after every confirmation or correction:

| Method | Path | Purpose |
|---|---|---|
| PUT | `/ownership/households/{id}/observations` | Add or update observations by `observation_id` (idempotent); `?replace=true` replaces the whole history (an empty list is allowed only with `replace=true`, to clear a household) |
| DELETE | `/ownership/households/{id}/observations/{observation_id}` | Remove one |
| GET | `/ownership/households/{id}/history` | History size, whether there is enough to predict, and a `digest` of the stored labels the backend compares with its own |

An observation has a `purchased_on` date, a `description`, `member_ids` (who was in the household then) and `owner_ids`.
A correction resends the same `observation_id` with the original date. Each household is one JSONL file under
`DIVVY_ML_DATA_DIR` (default `./data`), in the format `python -m divvy_ml.eval --data <file>` reads. Household ids are
letters, digits, `-` and `_`, up to 64 characters.

## Predicting

`POST /ownership/predict` takes the `household_id`, the household's current `people` and the `items` (id and
description). It looks up the household's stored history and returns, per item:

- `status`: `predicted` or `unknown`. Unknown means the household has under 30 labelled items, or nothing in its history
  resembles the item (see Calibration and unknown). An unknown item has no owners and confidence 0.
- `owners`: the predicted person ids, among the people in the request. Archived people are not in the request, so they are
  never predicted; a person the model has never seen is never predicted either.
- `confidence`: the calibrated chance the whole owner set is right, so a threshold such as 0.85 means 85%.
- `alternatives`: up to three other likely owner sets, with their raw model probability.

The response carries `model_version`, for example `combined-calibrated-1000-1fc3a8df` (model, history size, hash of the
history). It changes whenever the history does. `DIVVY_MODEL` picks the model (default `combined-calibrated`).

A household's model is built the first time it is needed (about 1 s at 1,000 items) and cached. Writes keep it fresh
without slowing predictions down:

- A write queues a background refit after `DIVVY_REFIT_DELAY` seconds (default 1). Writes that arrive inside that
  window share the one refit, so 20 corrections in a burst cause a single rebuild.
- The refit builds a new model instance off to the side and swaps it in as a whole. Predictions use the current
  model until then and never see a half-fitted one. A failed refit is logged and the old model keeps serving.
- A refit at 1,200 items takes about 0.3 s (the logistic heads use liblinear, 2.7 times faster than lbfgs for this shape). `DIVVY_REFIT_DELAY=sync` turns background refits off, and the next
  prediction after a write rebuilds the model and waits for it.

So a correction shows up in predictions within the delay plus one refit, not in the same instant. The latest
versions, with size, people and calibration holdout metrics, are saved in `models/<household>/meta.json` under the data
directory. The model itself is derived from the history, so only this metadata is persisted. A household with more than
5,000 items is fit on its 5,000 most recent. Thirty items predict in well under a second (checked by a test on a
1,000-item household).

Scheduled retraining runs alongside this. A background job checks every minute and retrains a household when its
history has grown by `DIVVY_RETRAIN_AFTER` observations (default 50), or when `DIVVY_RETRAIN_INTERVAL` seconds (default
86400, nightly) have passed since its last run and it has any new observations. `DIVVY_RETRAIN_INTERVAL=off` disables
it. Each run logs its start and end with the history size, model version and metrics (logger `divvy_ml.retrain`), and
`GET /ownership/retraining` lists the most recent 100 runs, newest first. A failed run is recorded and retried on the
next check.

In Docker the data directory is `/data`, a named volume (`mldata`) in `docker-compose.yml`.

## Receipt extraction evaluation

Ground-truth receipts live in `tests/data/receipts/`, one JSON per receipt (same shape as
`ExtractedReceipt`, money in cents). Score a directory of predictions with:

    python evaluate_extraction.py --predictions DIR [--truth tests/data/receipts]

It prints item price accuracy and the total reconciliation rate. The checked-in files are synthetic
placeholders (`tests/data/make_fixtures.py`); real labelled receipts still need to be added. Hand-labelled real receipts go in `tests/data/labelled/` (kept apart so the
synthetic set above stays at four); `test_labelled_receipts.py` checks each one adds up. The first is a Walmart receipt,
where one blurry price was read from the printed subtotal.


## Product name normalisation

`divvy_ml.normalize.normalize_product_name` turns a raw receipt line (`ORG WHL MLK 1GAL`) into a readable lowercase name (`organic whole milk`). It is rule based: it drops sizes, weights, prices and numeric codes, then expands abbreviations from the dictionary in `divvy_ml/normalize/abbreviations.py`. Unknown tokens are kept as they are, with an optional LLM pass on top (below). Trailing units with no number (`GV WHOLE GAL`) are dropped, and store brands such as `GV` expand to `great value`.

When an API key is set, one extra text-only call to the same Qwen API turns the rule-based names into better ones: the
rule name is sent as a draft, unseen names go in one batch per request, and answers are cached per printed name. An
unknown abbreviation is kept as printed rather than guessed. If the call fails, or an answer is not name-shaped, the
rule name stays, so normalising never fails a request. It reads the same `QWEN_API_KEY` (or `QWEN_API_KEY_FILE`) and
`QWEN_BASE_URL` as extraction. `QWEN_TEXT_MODEL` picks the model for this call (default `QWEN_MODEL`, so `qwen3.7-plus`)
and `QWEN_LLM_NORMALIZE=false` turns it off. `POST /normalize` with `{"names": [...]}` returns a name per printed line for
hand-entered items and history, using the same path.

`POST /receipts/extract` fills `normalized_name` on every item (the printed `name` is kept as it is), so the backend can store the readable name as the item's description and keep the printed text.
