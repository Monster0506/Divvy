## Households
- POST /households  {"name": "...", "defaultCurrency": "USD" (optional)} -> 201
- GET /households/{id} -> 200 | 404
- PATCH /households/{id}  {"name": "..."} -> 200 | 400 | 404
Name is trimmed and must be 1-80 characters (400 otherwise). Unknown or malformed ids return 404.
Errors use the standard ApiError body. All routes require the X-Api-Key header.

- PATCH /households/{id}/people/{personId}  {"displayName": "...", "venmoUsername": "..."} -> 200 | 400 | 404
  Send either field or both; absent fields are unchanged (400 if neither is sent). `venmoUsername` is where the person
  receives payments. A leading "@" and surrounding spaces are removed, and it must then be 5-30 letters, digits, `-` or
  `_` (400 otherwise). An empty `venmoUsername` removes it. People are returned with `venmoUsername` when they have one.
  Only the username is stored; Divvy never holds payment credentials.
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

## Grocery list
The household's shared list. Items are `{"id", "householdId", "text", "quantity", "notes", "addedByPersonId", "addedByName",
"status" ("open" or "purchased"), "purchasedAt", "createdAt", "updatedAt"}`; optional fields are left out when empty.
`quantity` is free text ("2", "1 dozen"). Unknown or malformed household or item ids, and items of another household, return 404.
- GET /households/{id}/list -> 200 | 404
  Open items first, then purchased ones, each oldest first.
- POST /households/{id}/list  {"text": "Eggs", "quantity": "1 dozen", "notes": "...", "addedByPersonId": "..."} -> 201 | 400 | 404
  Only `text` is required. Text is trimmed and must be 1-200 characters, quantity at most 50 and notes at most 1000.
  `addedByPersonId` must be an active member of the household.
- GET /households/{id}/list/{itemId} -> 200 | 404
- PATCH /households/{id}/list/{itemId}  {"text", "quantity", "notes", "purchased": true} -> 200 | 400 | 404
  Absent fields are unchanged; an empty `quantity` or `notes` clears it. `purchased: true` checks the item off and sets
  `purchasedAt` (checking it off again keeps the first time); `false` puts it back on the list.
- DELETE /households/{id}/list/{itemId} -> 204 | 404
- GET /households/{id}/list/autocomplete?q=egg&limit=10 -> 200 | 400 | 404
  `{"suggestions": ["Eggplant", "Large eggs"]}`: item names from the household's confirmed receipts that contain `q`
  (ignoring case), names starting with it first, then the most often bought. `limit` is 1-50 (default 10).

## Balances
- GET /households/{id}/balances -> 200 | 404
  {"balances": [{"currency": "USD", "people": [{"personId", "displayName", "netMinor", "owes": [{"personId", "amountMinor"}], "owedBy": [...], "venmoUsername"}]}]}
  `venmoUsername` is present for people who have set one, so settle-up can offer Pay with Venmo.
  One block per currency in the ledger (the household's default currency is always present). `netMinor` is positive when
  the person is owed money and negative when they owe it; nets in a currency always sum to zero. `owes`/`owedBy` are
  pairwise debts after netting opposite directions, not simplified across people. People who have left are listed only
  while they have a balance.
- GET /households/{id}/ledger?personId=  -> 200 | 400 | 404
  The ledger entries behind the balances, oldest first. Each has `sourceType` (`receipt` or `settlement`) and `sourceId`,
  and `reversesEntryId` when it cancels an earlier entry. With `personId`, only entries involving that person are listed,
  each with `personEffectMinor` (+ when they are the creditor, - when the debtor); these add up to their net balance.
  400 for a malformed `personId` or one outside the household.