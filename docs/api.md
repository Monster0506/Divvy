## Households
- POST /households  {"name": "...", "defaultCurrency": "USD" (optional)} -> 201
- GET /households/{id} -> 200 | 404
- PATCH /households/{id}  {"name": "..."} -> 200 | 400 | 404
Name is trimmed and must be 1-80 characters (400 otherwise). Unknown or malformed ids return 404.
Errors use the standard ApiError body. All routes require the X-Api-Key header.
## People
- POST /households/{id}/people  {"name": "..."} -> 201 | 400 | 404
- GET /households/{id}/people[?includeArchived=true] -> 200 | 404
- PATCH /households/{id}/people/{personId}  {"name": "..."} -> 200 | 400 | 404
Names are trimmed, 1-80 characters, and unique case-insensitively among a household's active people (400 otherwise; enforced by the person_active_name_key index). People have no accounts.