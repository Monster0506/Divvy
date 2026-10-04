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
