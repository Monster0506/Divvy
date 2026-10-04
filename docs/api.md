## Households
- POST /households  {"name": "...", "defaultCurrency": "USD" (optional)} -> 201
- GET /households/{id} -> 200 | 404
- PATCH /households/{id}  {"name": "..."} -> 200 | 400 | 404
Name is trimmed and must be 1-80 characters (400 otherwise). Unknown or malformed ids return 404.
Errors use the standard ApiError body. All routes require the X-Api-Key header.

## People
- POST /households/{id}/people  {"displayName": "..."} -> 201 | 400 | 404
- GET /households/{id}/people -> 200 (active members in the order they were added) | 404
- PATCH /households/{id}/people/{personId}  {"displayName": "..."} -> 200 | 400 | 404
Names are trimmed and must be 1-80 characters. A name already used by an active person in the same household (ignoring case) returns 400.
