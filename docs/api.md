## Households
- POST /households  {"name": "...", "defaultCurrency": "USD" (optional)} -> 201
- GET /households/{id} -> 200 | 404
- PATCH /households/{id}  {"name": "..."} -> 200 | 400 | 404
Name is trimmed and must be 1-80 characters (400 otherwise). Unknown or malformed ids return 404.
Errors use the standard ApiError body. All routes require the X-Api-Key header.
## Receipt edits
Edits apply to draft receipts. A confirmed receipt returns 409 (`conflict`) for every edit. Unknown or malformed receipt and item ids return 404. Every response is the full receipt with its items and a `reconciliation` block: `itemsTotalMinor`, `totalDifferenceMinor` (total minus items plus tax), `subtotalDifferenceMinor` (null without a subtotal) and `reconciled`.
- PATCH /receipts/{id}  any of `merchantName`, `purchasedAt` (ISO-8601), `paidByPersonId` (must be in the receipt's household), `taxMinor`, `totalMinor` (not negative) -> 200 | 400 | 404 | 409
- POST /receipts/{id}/items  {"description", "totalMinor", "quantity"?, "unitPriceMinor"?, "discountMinor"?} -> 201 | 400 | 404 | 409
- PATCH /receipts/{id}/items/{itemId}  any of the same fields -> 200 | 400 | 404 | 409
- DELETE /receipts/{id}/items/{itemId} -> 200 | 404 | 409
- POST /receipts/{id}/items/merge  {"itemIds": [two or more], "description"?} -> 200 | 400 | 404 | 409. Totals, discounts and quantities are summed into the first item (lowest line number), which keeps its owners; the others are removed.
