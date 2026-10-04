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
