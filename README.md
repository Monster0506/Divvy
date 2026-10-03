# Divvy

A Kotlin Multiplatform app (Compose Multiplatform) targeting Android, iOS, and the web (Wasm). See [product-vision.md](product-vision.md) and [project-structure.md](project-structure.md) for background.

## Project layout

- `composeApp/` – shared Compose UI and logic (Android library, iOS framework, Wasm web app)
- `androidApp/` – Android application entry point
- `iosApp/` – Xcode project for the iOS app

## Prerequisites

- **JDK 17 or newer**
- **Android Studio** (latest stable) with the Android SDK installed – compile SDK 37, min SDK 24
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

## Continuous integration

GitHub Actions runs each workflow only when its files change, so a docs-only change runs no build jobs:

| Workflow | Runs when | Does | Typical time |
|----------|-----------|------|--------------|
| **Gradle** (`.github/workflows/gradle.yml`) | PRs and pushes to `main` touching `composeApp/`, `androidApp/`, `server/`, `gradle/` or the build scripts | `./gradlew :composeApp:check :androidApp:assembleDebug :server:test` on JDK 17; uploads test reports on failure | ~4–5 min |
| **ML service** (`.github/workflows/ml.yml`) | PRs and pushes to `main` touching `ml/` | ruff, pytest and a Docker build with a `/health` check. See [ml/README.md](ml/README.md). | ~20 s per job (parallel) |
| **iOS** (`.github/workflows/ios.yml`) | Nightly at 06:17 UTC, manual trigger, and PRs touching `composeApp/src/iosMain/` or `iosApp/` | `:composeApp:linkDebugFrameworkIosSimulatorArm64` on macOS | IOS_TIME |

To run the iOS build by hand, use **Actions → iOS → Run workflow**, or run `gh workflow run ios.yml`.
