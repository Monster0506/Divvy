# App launch routing

On launch the app reads the stored household id (multiplatform-settings: SharedPreferences on Android,
NSUserDefaults on iOS, localStorage on web).

- No stored id: onboarding.
- Stored id and the household loads: home.
- Stored id but the household is gone (not found): the id is cleared and onboarding shows.
- Stored id but the server is unreachable: the id is kept and home opens.

Finishing onboarding stores the household id so the next launch opens the household.
`LocalHouseholdLookup` is a placeholder that always reports "found"; replace it with the server call
(`GET /households/{id}`, see docs/api/households.md) once the app has an API client.