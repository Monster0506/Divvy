# 0002: How the ML service gets a household's history

Status: accepted. Issue: #163.

## Context

Ownership prediction learns from one household's confirmed items and owners. The model needs that history
every time it predicts, and the request in #41 only carries the items to predict. Something has to decide where
the history lives and how the ML service sees it. The history grows with every confirmed receipt and every
correction, a correction must change the next prediction, and a prediction for a 30-item receipt must come back
in under a second.

## Options

**A. The backend sends the whole history with every predict call.**
Stateless ML service and nothing to sync. But a household with 5,000 items sends hundreds of kilobytes per
receipt, the service re-embeds and re-fits on every call (about 0.5 to 1.5 s at 1,000 items), and nothing can be
cached between calls.

**B. The ML service reads Postgres directly.**
One source of truth. But the ML service now depends on the backend's schema and needs database credentials,
migrations can break prediction silently, and two services write to the same tables' neighbours.

**C. The backend pushes changes; the ML service keeps its own copy.**
After a receipt is confirmed or corrected the backend sends the changed observations. The ML service stores
them, keeps a fitted model per household and refits in the background. Prediction needs only the items.

## Measured cost

Fitting a household from scratch and predicting a 30-item receipt, on the development machine (hashing embedder,
synthetic household, `python` timing of `fit` then `predict`):

| observations | model | fit | predict 30 items |
|---|---|---|---|
| 1,000 | knn | 0.01 s | 0.22 s |
| 1,000 | combined | 0.31 s | 0.17 s |
| 1,000 | combined-calibrated | 0.64 s | 0.12 s |
| 5,000 | knn | 0.01 s | 0.06 s |
| 5,000 | combined | 1.80 s | 0.18 s |
| 5,000 | combined-calibrated | 3.25 s | 0.16 s |

Prediction is fast at any size, so the 1 second budget in #114 holds. Fitting is not: option A would pay 0.6 s at
1,000 observations and 3.3 s at 5,000 on every request, which is why the fitted model is cached and refit off the
request path.

## Decision

Option C. The backend stays the source of truth; the ML service holds a derived, rebuildable copy.

- `PUT /ownership/households/{household_id}/observations` upserts observations, keyed by the backend's
  `observation_id`. It is idempotent, so a retry is safe. `?replace=true` replaces the whole history, which is
  how the backend resyncs after an outage or a restore.
- `DELETE /ownership/households/{household_id}/observations/{observation_id}` removes one.
- `GET /ownership/households/{household_id}/history` reports the size and whether the household has enough
  history for predictions.
- `POST /ownership/predict` stays as in #41 and uses the stored history.

An observation carries its purchase date, description, the people who were in the household then
(`member_ids`) and who owns it (`owner_ids`). `member_ids` is required: a person missing from an old
observation is unknown to the model, never a "no" (#164). A correction sends the same `observation_id` with new
owners and keeps the original date, so time-ordered evaluation does not leak (#108).

The ML service stores each household as one JSONL file in the format `python -m divvy_ml.eval` already reads, under
`DIVVY_ML_DATA_DIR` (`/data` in the image, a named volume in compose). A file can be evaluated directly.

## Consequences

- Prediction latency does not depend on history size, and the model can be cached and refit off the request path.
- Two copies of the data exist. The ML copy is derived: if it is lost or wrong, `?replace=true` rebuilds it, and the
  history size in `GET .../history` lets the backend check it matches.
- The backend must push after every confirmation and correction (#115, #119, #120) and handle the ML service being
  down by retrying; prediction falls back to manual assignment meanwhile (#118).
- Single instance only: the store is a local file with an in-process lock. Running several ML replicas needs a shared
  store, which is out of scope for one 8 GB server.
- Households never share files, models or caches, so one household's data cannot reach another's predictions.
