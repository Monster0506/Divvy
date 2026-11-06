# App launch routing

On launch the app reads the stored household id (multiplatform-settings: SharedPreferences on Android,
NSUserDefaults on iOS, localStorage on web).

- No stored id: onboarding.
- Stored id and the household loads: home.
- Stored id but the household is gone (not found): the id is cleared and onboarding shows.
- Stored id but the server can't be used (unreachable or the key is rejected): the id is kept and home opens,
  which sends the app to Account > Server (below).
- Stored id `local`, saved by early builds that ran without a server: the id is cleared and onboarding shows.

Finishing onboarding stores the household id so the next launch opens the household. `ServerHouseholdLookup`
checks it with `GET /households/{id}`.

## When the server can't be used

`ConnectionMonitor` checks `GET /health` and `GET /auth/check` at launch, after the server settings change, and
whenever a call fails to reach the server or gets a 401. While it reports the server unreachable or the key
rejected, every screen is replaced by Account > Server: one message saying which, the server URL and API key
fields, and no tabs. When a later check passes, the app goes back to the screen it left.

Nothing is filled in with sample data while the server is loading. Account says "Loading…" for the household
name, members and auto-assign settings until they arrive. If the phone has not said which member it is, or that
member is no longer in the household, the app asks "Who are you?" instead of picking someone.