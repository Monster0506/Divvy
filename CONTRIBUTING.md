# Contributing

Work happens on a branch off `main` and lands through a pull request that references its issue (`Closes #N`). CI must be green before merging.

## Running the CI checks locally

Run these from the repository root unless noted.

| Workflow | What it runs | Locally |
| --- | --- | --- |
| Gradle | Build and test the app and server | `./gradlew :composeApp:check :androidApp:assembleDebug :server:test --continue` |
| Server tests | Flyway migrations and repository tests against Postgres (Testcontainers) | `./gradlew :server:test` (needs Docker running) |
| ML service | Lint, format check, tests, Docker image | see below |
| iOS | Links the iOS framework (nightly, and on iOS-only changes) | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` (macOS only) |

ML service, from `ml/`:

```bash
pip install -e ".[dev]"
ruff check .
ruff format --check .
pytest
docker build -t divvy-ml:ci .
```

JDK 17 or newer is needed for the Gradle checks; the ML service needs Python 3.12.

## Branch protection on `main`

Repo admins should enable these under Settings > Branches > Branch protection rules for `main`:

- Require a pull request before merging.
- Require status checks to pass before merging, with "Require branches to be up to date" on.
- Required checks: `Build and test` (Gradle), `Lint and test` and `Docker image` (ML service).
- Do not allow force pushes or deletion of `main`.

Notes on the checks:

- The Gradle and ML workflows use `paths` filters, so they are skipped on changes outside their areas (a docs-only PR, say). A skipped required check stays "Expected" and blocks the merge. If that gets in the way, admins can add an always-running aggregate job or leave those checks unrequired.
- `Migrations and repository tests` (Server tests) and `Link iOS framework` (iOS) are not required: the former is covered by the Gradle job's `:server:test`, and the latter is nightly and only runs on iOS changes.
