# Divvy

[![Gradle](https://github.com/Monster0506/Divvy/actions/workflows/gradle.yml/badge.svg)](https://github.com/Monster0506/Divvy/actions/workflows/gradle.yml)
[![ML service](https://github.com/Monster0506/Divvy/actions/workflows/ml.yml/badge.svg)](https://github.com/Monster0506/Divvy/actions/workflows/ml.yml)
[![Server tests](https://github.com/Monster0506/Divvy/actions/workflows/server-tests.yml/badge.svg)](https://github.com/Monster0506/Divvy/actions/workflows/server-tests.yml)
[![iOS](https://github.com/Monster0506/Divvy/actions/workflows/ios.yml/badge.svg)](https://github.com/Monster0506/Divvy/actions/workflows/ios.yml)

A Kotlin Multiplatform app (Compose Multiplatform) targeting Android, iOS, and the web (Wasm). See [product-vision.md](product-vision.md) and [project-structure.md](project-structure.md) for background.

See [CONTRIBUTING.md](CONTRIBUTING.md) for running the CI checks locally and the branch protection settings.

## Project layout

- `composeApp/` - shared Compose UI and logic (Android library, iOS framework, Wasm web app)
- `androidApp/` - Android application entry point
- `iosApp/` - Xcode project for the iOS app

## Prerequisites

- **JDK 17 or newer**
- **Android Studio** (latest stable) with the Android SDK installed - compile SDK 37, min SDK 24
- **For iOS:** a Mac with Xcode, plus an Apple Developer account to run on a physical device
- No separate Gradle install is needed; use the included Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows)

## Installation

```bash
git clone https://github.com/Monster0506/Divvy.git
cd Divvy
```

Open the project root in Android Studio and let Gradle sync, or verify the setup from the command line:

```bash
./gradlew build
```

## Running

### Android

From Android Studio, select the `androidApp` run configuration and choose an emulator or connected device.

From the command line, with a device or emulator connected:

```bash
./gradlew :androidApp:installDebug
```

### Server

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/divvy DATABASE_USER=divvy DATABASE_PASSWORD=divvy ML_SERVICE_URL=http://localhost:8000 API_KEY=dev-key ./gradlew :server:run
```

The Ktor server listens on port 8080 by default. Configuration comes from environment variables:

| Variable | Required | Description |
| --- | --- | --- |
| `PORT` | no | Listening port (default 8080) |
| `DATABASE_URL` | yes | JDBC URL of the Postgres database |
| `DATABASE_USER` | yes | Database user |
| `DATABASE_PASSWORD` | yes | Database password |
| `ML_SERVICE_URL` | yes | Base URL of the ML service |
| `API_KEY` | yes | Shared API key, sent by clients in the `X-Api-Key` header |
| `APP_VERSION` | no | Version reported by `/health` (default 1.0) |

The server refuses to start if a required variable is missing. `GET /health` returns `{"status":"ok","version":"..."}` and is public. Every other route requires the `X-Api-Key` header to match `API_KEY`, otherwise the server returns 401. Locally, `API_KEY=dev-key` works with `curl -H "X-Api-Key: dev-key" http://localhost:8080/...`.

Receipt images are stored behind the `ImageStorage` interface (`put`, `get`, `delete`). `LocalFileImageStorage` writes files into a configured directory (a Docker volume in deployment) under generated UUID keys. Keys are validated as canonical UUIDs, so client filenames and path traversal never reach the filesystem.

Run the server tests with `./gradlew :server:test`.

### Web (Wasm)

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

This starts a dev server and opens the app in your browser.

To rebuild and reload the browser automatically whenever you save a file, add `--continuous`:

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun --continuous
```

The dev server uses port 8080, the same default as the Ktor server, so set `PORT` to something else (e.g. `PORT=8081`) when running the server alongside it.

### iOS

1. Set your Apple Developer team ID in `iosApp/Configuration/Config.xcconfig`:
   ```
   TEAM_ID=YOUR_TEAM_ID
   ```
2. Open `iosApp/iosApp.xcodeproj` in Xcode.
3. Select a simulator or connected iPhone and click **Run**.

See JetBrains' guide on [running on a real iOS device](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-create-first-app.html#run-on-a-real-ios-device) for device setup details.

## ML service

The Python ML service lives in `ml/`. See [ml/README.md](ml/README.md) for setup, run, test and lint commands.

## Deployment

The backend runs as a Docker Compose stack on a single host (target: 4 vCPU / 8 GB / Linux). Only `server` is reachable from the host, and only on `127.0.0.1:8080`. `ml` and `db` live on an internal Docker network with no published ports.

| Service | Image | Memory limit | CPU limit | Data |
|---|---|---|---|---|
| `db` | `pgvector/pgvector:pg16` | 1.5 GB | 1.5 | `pgdata` volume |
| `ml` | built from `ml/` | 3 GB | 2.0 | none |
| `server` | built from `server/Dockerfile` | 1.5 GB | 1.5 | `receipts` volume |

The `receipts` volume is mounted at `/data/receipts` for uploaded receipt images. Receipt upload is not implemented yet, so the volume is currently empty.

The `receipts` volume is mounted at `/data/receipts` for uploaded receipt images. Receipt upload is not implemented yet, so the volume is currently empty.

### Prerequisites

- Docker Engine with the Compose v2 plugin (`docker compose version`)
- Git

### First deploy

```bash
git clone https://github.com/Monster0506/Divvy.git
cd Divvy
cp .env.example .env
```

Edit `.env` and set real values. Generate secrets with `openssl rand -hex 24`. Then:

```bash
docker compose up -d --build
docker compose ps
```

The first build takes several minutes. All three services should reach `healthy`. Verify with `curl http://<server-ip>:8080/health`.
The server speaks plain HTTP. The X-Api-Key header travels unencrypted, so don't use this setup for real user data without TLS.


```
api.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

### Updating

```bash
git pull
docker compose up -d --build
```

Named volumes survive this. Never run `docker compose down -v`, which deletes the database and uploaded images.

### Backups

Back up both volumes. Run these from the repo root.

**Database dump:**

```bash
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > divvy-$(date +%F).dump
```

**Database restore** (replaces existing data):

```bash
docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < divvy-2026-01-01.dump
```

**Receipt images:**

```bash
docker run --rm -v divvy_receipts:/data -v "$PWD":/backup alpine tar czf /backup/receipts-$(date +%F).tgz -C /data .
```

To restore the images, extract the archive into the volume the same way:

```bash
docker run --rm -v divvy_receipts:/data -v "$PWD":/backup alpine tar xzf /backup/receipts-2026-01-01.tgz -C /data
```

Copy backups off the server. A backup that lives only on the same disk is not a backup.

### Notes

- `ml` has no outbound internet access (internal network). Model files must be baked into its image.
- Changing `POSTGRES_PASSWORD` in `.env` does not change an existing database. Alter the role inside Postgres instead.

## Continuous integration

GitHub Actions runs each workflow only when its files change, so a docs-only change runs no build jobs:

| Workflow | Runs when | Does | Typical time |
|----------|-----------|------|--------------|
| **Gradle** (`.github/workflows/gradle.yml`) | PRs and pushes to `main` touching `composeApp/`, `androidApp/`, `server/`, `gradle/` or the build scripts | `./gradlew :composeApp:check :androidApp:assembleDebug :server:test` on JDK 17; uploads test reports on failure | ~4-5 min |
| **ML service** (`.github/workflows/ml.yml`) | PRs and pushes to `main` touching `ml/` | ruff, pytest and a Docker build with a `/health` check. See [ml/README.md](ml/README.md). | ~20 s per job (parallel) |
| **iOS** (`.github/workflows/ios.yml`) | Nightly at 06:17 UTC, manual trigger, and PRs touching `composeApp/src/iosMain/` or `iosApp/` | `:composeApp:linkDebugFrameworkIosSimulatorArm64` on macOS | IOS_TIME |

To run the iOS build by hand, use **Actions > iOS > Run workflow**, or run `gh workflow run ios.yml`.
