## Households
- POST /households  {"name": "...", "defaultCurrency": "USD" (optional)} -> 201
- GET /households/{id} -> 200 | 404
- PATCH /households/{id}  {"name": "..."} -> 200 | 400 | 404
Name is trimmed and must be 1-80 characters (400 otherwise). Unknown or malformed ids return 404.
Errors use the standard ApiError body. All routes require the X-Api-Key header.

## Receipt assignment
- PUT /receipts/{id}/items/{itemId}/owners  {"owners": [{"personId": "...", "weight": 1 (optional)}]} -> 200 | 400 | 404 | 409
- POST /receipts/{id}/confirm -> 200 | 400 | 404 | 409
Owners replace the existing owners of the item and must be distinct, active members of the receipt household (400 otherwise). An empty list unassigns the item. Assignments are stored with source `user` and logged in the ownership history.
Confirm returns 400 with the unassigned item ids in `details` if any item has no active owner, and moves the receipt from draft to confirmed in one transaction. Non-draft receipts return 409.
