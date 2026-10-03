# Divvy ML service

FastAPI service for receipt extraction and ownership prediction. Requires Python 3.12.

```
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
make install
make run     # uvicorn divvy_ml.app:app on :8000
make test    # pytest
make lint    # ruff check
```

Without make, run the commands from the Makefile directly.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness check |
| POST | `/receipts/extract` | Extract a structured receipt from an image (currently returns a fixed sample) |

`POST /receipts/extract` takes exactly one of `image_id` (an image already in receipt storage) or `image_base64`, and returns an `ExtractedReceipt` (see `divvy_ml/receipts/schemas.py`). All money fields are integers in cents. Interactive docs are at `/docs` while the service is running.
