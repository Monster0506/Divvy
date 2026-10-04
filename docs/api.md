## Households
- POST /households  {"name": "...", "defaultCurrency": "USD" (optional)} -> 201
- GET /households/{id} -> 200 | 404
- PATCH /households/{id}  {"name": "..."} -> 200 | 400 | 404
Name is trimmed and must be 1-80 characters (400 otherwise). Unknown or malformed ids return 404.
Errors use the standard ApiError body. All routes require the X-Api-Key header.

- POST /households/{id}/people/{personId}/archive -> 200 | 404
  Marks the person as having left. Archived people are hidden from GET /households/{id}/people unless
  ?includeArchived=true, cannot be newly assigned to receipt items (ArchivedPersonException in the
  repository), and still appear as owners on existing receipts. Archiving twice is harmless.

## Receipts
- GET /households/{id}/receipts -> 200 | 400 | 404
Newest first by purchase time, with cursor pagination. Query parameters, all optional:
`status` (draft, confirmed or void), `merchant` (exact name, ignoring case), `from` (inclusive) and `to` (exclusive) as an ISO-8601 date or time, `paidBy` (person id), `limit` (1-100, default 50) and `cursor` (the `nextCursor` of the previous page).
Response: `{"items": [{"id", "merchantName", "purchasedAt", "paidByPersonId", "currency", "totalMinor", "status", "itemCount"}], "nextCursor": "..." | absent on the last page}`.
- POST /households/{id}/receipts  multipart/form-data with a file part named `image` -> 201 {"id": "...", "status": "processing"}
  - 400 if the body is not multipart or has no `image` part
  - 404 for an unknown or malformed household id
  - 413 if the image is larger than `MAX_UPLOAD_BYTES` (default 10 MiB)
  - 415 if the file is not a JPEG, PNG or HEIC (checked by magic bytes, not the declared content type)

The image is stored under `IMAGE_DIR` (default `data/images`). The receipt starts in `processing` status with no payer or amounts.

## Settlements
- POST /households/{id}/settlements  {"fromPersonId": "...", "toPersonId": "...", "amountMinor": 2500, "note": "Venmo" (optional)} -> 201 | 400 | 404
  Records that `fromPersonId` paid `toPersonId`. Also writes a ledger entry that reduces what the payer owes the receiver.
  The currency is the household's default. Overpaying is allowed and flips the balance.
  - 400 for a non-positive amount, the same person on both sides, a person outside the household, a missing field or malformed JSON
  - 404 for an unknown or malformed household id
- GET /households/{id}/settlements -> 200, newest first | 404
