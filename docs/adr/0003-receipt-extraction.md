# 0003: How receipt images become line items

Status: accepted, with the accuracy and latency numbers still to be measured. Issue: #72.

## Context

`POST /receipts/extract` has to turn a phone photo of a receipt into items, prices, totals and a date. Photos are
skewed, creased and unevenly lit, and store layouts differ. People review every extraction before it counts, so
the aim is a good first draft with honest warnings, not a perfect parse.

## Options

**A. OCR (Tesseract or PaddleOCR) plus a rule-based parser.**
Runs on our own hardware and nothing leaves the server. But the parser has to be written and maintained per
layout (columns, quantity lines, coupons, multi-line names), and OCR errors on creased photos feed straight into
it. Accuracy on an unseen store is the weak point.

**B. A vision language model, asked for strict JSON.**
One call handles layout, abbreviations, quantities and coupons, and the answer is constrained by a JSON schema.
Costs a network call, an API key and a third party seeing the receipt image. It can also make things up, so it is
told to transcribe only and its numbers are checked afterwards.

## Decision

Option B with Qwen (`qwen3.7-plus`) through an OpenAI-compatible endpoint, as in the receipt-bot prototype.

- The prompt allows transcription only and forbids inferring products. Totals are transcribed, never calculated.
- The model answers in dollars; the service converts to integer cents and normalises the printed date, leaving
  `purchased_on` null when the format is unknown.
- Arithmetic checks (line total, items against subtotal, subtotal plus tax against total) are reported as
  `warnings` and never change a value, so a misread is visible to the person reviewing.
- The key, endpoint, model and thinking budget come from environment variables. Without a key the endpoint
  answers 503 and the rest of the service is unaffected.

## Consequences

- Receipt images go to the vision provider. This is acceptable for the household use case but should be stated
  to users, and the provider is replaceable because the call sits behind one class.
- The service needs outbound network access, so in Docker Compose the `ml` service joins the `edge` network
  (no ports are published).
- The acceptance targets (at least 90% of items with the right price, under 30 seconds) have not been measured on
  real receipts yet. `ml/evaluate_extraction.py` scores a directory of extractions against labelled receipts, and
  `ml/tests/test_receipts_live.py` times one real call. Record the results here once the labelled set exists, and
  revisit option A if the numbers miss.
