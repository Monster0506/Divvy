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
- First real measurement, one photographed Walmart receipt (14 items, rotated, downscaled to 1600 px), against a
  hand-labelled copy in `ml/tests/data/labelled/`. With thinking on (budget 512) the call took 31.9 s and 12 of 14
  prices were right (86%). With thinking off it took 14.3 s and 11 of 14 were right. Store, date, subtotal, tax and
  total were correct both times, item names had small misreads (`4CT COHIP`, `GVM02SHRD32Z`), and the subtotal check
  flagged both runs because the item prices did not add up to it.
- That is one receipt, so it neither meets nor misses the 90% target yet, but the time is over 30 s with thinking on.
  Next: label more real receipts, score them with `ml/evaluate_extraction.py`, and compare thinking settings,
  smaller images and a faster model. Revisit option A if the numbers miss.
