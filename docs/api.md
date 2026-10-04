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

## Receipt assignment
- PUT /receipts/{id}/items/{itemId}/owners  {"owners": [{"personId": "...", "weight": 1 (optional)}]} -> 200 | 400 | 404 | 409
- POST /receipts/{id}/confirm -> 200 | 400 | 404 | 409
Owners replace the existing owners of the item and must be distinct, active members of the receipt household (400 otherwise). An empty list unassigns the item. Assignments are stored with source `user` and logged in the ownership history.
Confirm returns 400 with the unassigned item ids in `details` if any item has no active owner, and moves the receipt from draft to confirmed in one transaction. Non-draft receipts return 409.

## Receipt edits
Edits apply to draft receipts. A confirmed receipt returns 409 (`conflict`) for every edit. Unknown or malformed receipt and item ids return 404. Every response is the full receipt with its items and a `reconciliation` block: `itemsTotalMinor`, `totalDifferenceMinor` (total minus items plus tax), `subtotalDifferenceMinor` (null without a subtotal) and `reconciled`.
- PATCH /receipts/{id}  any of `merchantName`, `purchasedAt` (ISO-8601), `paidByPersonId` (must be in the receipt's household), `taxMinor`, `totalMinor` (not negative) -> 200 | 400 | 404 | 409
- POST /receipts/{id}/items  {"description", "totalMinor", "quantity"?, "unitPriceMinor"?, "discountMinor"?} -> 201 | 400 | 404 | 409
- PATCH /receipts/{id}/items/{itemId}  any of the same fields -> 200 | 400 | 404 | 409
- DELETE /receipts/{id}/items/{itemId} -> 200 | 404 | 409
- POST /receipts/{id}/items/merge  {"itemIds": [two or more], "description"?} -> 200 | 400 | 404 | 409. Totals, discounts and quantities are summed into the first item (lowest line number), which keeps its owners; the others are removed.
