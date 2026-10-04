# Backend and ML service integration

The Ktor server talks to the ML service for two things: asking who owns the items on a receipt, and telling it which
labels people confirmed. The ML service owns a derived copy of each household's history (see
`docs/adr/0002-ml-household-history.md`); the database stays the source of truth.

## The client

`MlClient` (`server/.../ml/MlClient.kt`) has four calls: `predict`, `upsertObservations` (with `replace` to rebuild a
household from scratch), `deleteObservation` and `history`. `KtorMlClient` implements it over HTTP against
`ML_SERVICE_URL`; with no URL, `NoopMlClient` reports the service as not configured.

Every call returns an `MlResult`, never an exception for something the ML service did:

| Result | When | What callers do |
|---|---|---|
| `Success` | 2xx with a readable body | use the value |
| `Unavailable` | timeout, connection failure, 5xx, or a body that is not the contract | carry on without ML; try again later |
| `Rejected` | 4xx | the request itself is wrong; log the message, retrying will not help |

A prediction or a label push that fails must never fail the request that triggered it: confirming a receipt works
whether or not the ML service is up.

Timeouts are 2 s to connect and 10 s per request (the first prediction for a household builds its model). Path
segments are encoded, so an id can never change which endpoint is called.

## Checking the contract against the real service

The unit tests use a mock engine. `MlServiceContractTest` runs the client against a real ML service and is skipped
unless `ML_SERVICE_URL` is set:

```
cd ml && uvicorn divvy_ml.app:app --port 8000
ML_SERVICE_URL=http://127.0.0.1:8000 ./gradlew :server:test --tests "*MlServiceContractTest"
```

It pushes a household's history, predicts from it, corrects and deletes a label, and replaces the history.

## Keeping the ML copy current (label sync)

When a receipt is confirmed, its items' final owners are pushed to the ML service as labels; voiding it removes them.
`SyncingReceiptRepository` wraps `ReceiptRepository`, so the confirm route and any route added later get this without
calling the ML service themselves.

- **Background.** The push runs in the background after the database change, so a slow or hung ML service never delays
  the response. Changes to one household are sent in the order they were made; households do not wait for each other.
- **What is sent.** One observation per item that has an owner: its id (the item id, so a resend updates it), the
  purchase date (UTC), the description, the owners, and how it was labelled (`manual`, `predicted_accepted` or
  `predicted_corrected`, from the label source recorded at confirmation). Items nobody owns are skipped.
- **Members.** The people who were in the household when the receipt was confirmed. Someone who joined later is
  unknown for these items, not a "no", and someone archived afterwards still counts as a member of what was labelled
  while they lived there.
- **Failure.** If the ML service is unavailable the household is marked dirty and the request still succeeds. A rejected
  push is logged and not retried. Confirm and void never fail because of the ML service.

`LabelSyncDatabaseTest` drives the real repositories on a real Postgres with a fake ML client. `LabelSyncLiveTest` does
the same against a real ML service when `ML_SERVICE_URL` is set: confirming receipts grows the ML history and voiding
one shrinks it.

## Self-healing: checking and rebuilding the ML copy

A push can be missed (the ML service was down), and the ML volume can be lost or restored from an old backup. The
database is the source of truth, so the ML copy is checked against it and rebuilt when they differ.

- **How they are compared.** `GET /ownership/households/{id}/history` returns a `digest`: a SHA-256 over one line per
  label, sorted by id, as `id|date|description|owner ids|member ids` with the ids sorted and comma-joined. The backend
  builds the same text from its database (`historyDigest`), so a missed push, a missed delete **and a missed correction**
  all show up, including when the counts still match. Both sides pin the same fixture value in their tests, and
  `MlResyncLiveTest` checks they agree on real data.
- **When.** `MlResync.ensureSynced` runs before a prediction. It does nothing unless the household is marked dirty (a
  push failed) or has not been checked in the last five minutes, then compares digests and rebuilds with
  `PUT .../observations?replace=true` if they differ. An unreachable ML service counts as not checked, so it is tried again
  on the next call. It shares the household's lock with pushes, so a rebuild never interleaves with one.
- **Forcing it.** `GET /households/{id}/ml/status` shows the database and ML label counts, whether they match, whether the
  household is dirty and whether the ML service is reachable. `POST /households/{id}/ml/resync` rebuilds now and answers
  503 if the ML service is down or 502 if it refuses the history. A household with no labels is rebuilt as empty, which is
  why the ML service accepts an empty `replace`.
