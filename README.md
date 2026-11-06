# Divvy
Learns to split the receipt automatically

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

Debug builds install as a separate app, **Divvy Dev** (`com.monster0506.divvy.debug`), so they sit next to a release install.

### Installing a release

Signed APKs are on the [Releases](https://github.com/Monster0506/Divvy/releases) page:

- **Tagged releases** (`Divvy 1.2.0`, file `divvy-1.2.0.apk`) are deliberate versions.
- **Latest from main** (`latest-main`, file `divvy-main.apk`) is rebuilt on every merge to `main` that touches the app: [direct download](https://github.com/Monster0506/Divvy/releases/download/latest-main/divvy-main.apk).

Open the APK on the phone and allow installing from that source. Any newer release or main build installs over an older one.

Release APKs know the server address but not the API key, because releases are public. On first launch the app opens Account > Server and asks for the key. Paste it and save. To join an existing household, enter its name on the next screen and pick who you are.

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

The server refuses to start if a required variable is missing. `GET /health` returns `{"status":"ok","version":"..."}` and is public. Every other route requires the `X-Api-Key` header to match `API_KEY`, otherwise the server returns 401. `GET /auth/check` returns 204 when the key is accepted and 401 when it isn't; the app calls `/health` and then `/auth/check` on launch and whenever the server settings change, and shows one banner if the server is unreachable or rejects the key. Locally, `API_KEY=dev-key` works with `curl -H "X-Api-Key: dev-key" http://localhost:8080/...`.

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

### Taking a receipt photo

From Receipts, tap Scan receipt, then take a photo or choose one from Gallery. Review the prepared image and tap Upload photo. The app shows upload progress, keeps the image for retry after a failure, and displays the new receipt ID in `processing` state when the server saves it. Images are converted to a legible JPEG before upload. After the upload the app waits while the server reads the photo, then opens the review screen on the real receipt: fix or add lines, choose who owns each item, accept or change Divvy's guesses, and confirm. The Receipts tab lists the household's receipts from the server, a confirmed receipt shows each person's share, and it can be reopened for correction. Account holds the household's auto-assign switch and confidence threshold.

Start the Ktor server before uploading. The Android emulator uses `http://10.0.2.2:8080` by default; the iOS simulator uses `http://localhost:8080`. On a physical device, open **Account > Server**, enter a reachable server URL and its API key, then save. Use a deployed HTTPS URL, or for local development use `http://<your-computer-LAN-IP>:8080` while the phone and computer are on the same network. `localhost` on a phone points to the phone itself. iOS will request local-network access for a LAN server. You can also set build defaults with `-Pdivvy.apiBaseUrl=...` and `-Pdivvy.apiKey=...`; the in-app settings override them.

#### Building the app for a deployed server

Put the server address and key in the git-ignored `local.properties` at the repository root, next to `sdk.dir`:

```
divvy.apiBaseUrl=https://your-server.example
divvy.apiKey=the-api-key
```

`local.properties` wins over `gradle.properties` and `-P`, so every build installed from your machine, for example `./gradlew :androidApp:installDebug`, starts pointed at that server with no setup in the app. Never put the key in a tracked file. Gradle regenerates the setting when it changes, so no clean build is needed. The values are only defaults: once someone has saved a server in **Account > Server** on a device, that saved value is used instead, so clear the app data (or save the new server in Account) after switching. The app creates a server household on the first upload when onboarding has only stored a local placeholder ID.

The server must be built from a revision containing `POST /households/{id}/receipts` (this PR). If an older Docker server responds `404 Not Found` with an empty body, rebuild it from the updated source with `docker compose up -d --build server`. A current server responds to a nonexistent household with JSON like `{"code":"not_found","message":"household not found"}`. `GET /health` can confirm reachability, but does not verify the upload route. A proxy-generated `503 Service Unavailable` means the upstream service needs to be started or repaired before the app can upload. Do not share API or Qwen keys in issue comments.

## ML service

The Python ML service lives in `ml/`. See [ml/README.md](ml/README.md) for setup, run, test and lint commands.

## Deployment

The backend runs as a Docker Compose stack on a single host (target: 4 vCPU / 8 GB / Linux). Only `server` is published to the host, on port 8080; `ml` and `db` live on an internal Docker network with no published ports.

| Service | Image | Memory limit | CPU limit | Data |
|---|---|---|---|---|
| `db` | `pgvector/pgvector:pg16` | 1.5 GB | 1.5 | `pgdata` volume |
| `ml` | built from `ml/` | 3 GB | 2.0 | none |
| `server` | built from `server/Dockerfile` | 1.5 GB | 1.5 | `receipts` volume |

The `receipts` volume is mounted at `/data/receipts` for uploaded receipt images.

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
git pull --ff-only origin main
docker compose up -d --build
```

Named volumes survive this. Never run `docker compose down -v`, which deletes the database and uploaded images.

### Automatic server updates

The server can check public GitHub `main` about once a minute using a systemd timer. Setup runs directly in the server console: no SSH key, GitHub token or inbound webhook is needed. See the [console deployment guide](docs/hosting.md) for first setup.

For an existing Git checkout at `~/divvy`, with the stack already configured and running:

```bash
git -C "$HOME/divvy" remote set-url origin https://github.com/Monster0506/Divvy.git
sudo bash "$HOME/divvy/deploy/install-auto-update.sh"
```

The installer requires Linux/systemd, Git, Docker Compose v2 and `flock` (from `util-linux`). It resolves the checkout path and runs the service as its owner, with Docker group access when available. It runs an initial build immediately. After that, the timer fetches main and fast-forwards the checkout, then runs exactly:

```bash
docker compose up -d --build
```

It rebuilds only if `server/`, `gradle/`, `gradlew`, the root Gradle files, `.dockerignore` or `docker-compose.yml` changed since the last successful Compose run. App-only and docs-only commits are pulled without restarting containers. A lock prevents overlapping runs; a dirty checkout, another branch or local/diverged commits stop deployment rather than discarding changes.

`.env` stays on the server, and named volumes are not deleted. The deployed revision is recorded in `.git/divvy-server-deployed` only after Compose succeeds. Failed builds are tried again on the next timer tick, even though the checkout has already moved forward. This is not an automatic rollback: detached startup does not guarantee application health, and database migrations are not undone.

If the existing deployment was uploaded as an archive and has no `.git`, preserve it and its secrets before cloning. Run this once in the server console, only if `~/divvy-archive` does not exist:

```bash
test ! -e "$HOME/divvy-archive" &&
  mv "$HOME/divvy" "$HOME/divvy-archive" &&
  git clone --branch main https://github.com/Monster0506/Divvy.git "$HOME/divvy" &&
  sudo install -o "$(id -u)" -g "$(id -g)" -m 600 "$HOME/divvy-archive/.env" "$HOME/divvy/.env"
sudo bash "$HOME/divvy/deploy/install-auto-update.sh"
```

The archive remains as a backup; review any other local customizations before enabling updates. The Compose project stays named `divvy`, so the existing database and image volumes are reused. Once enabled, update through main rather than uploading an archive over the managed checkout.

Check the timer, read logs, or run a check immediately:

```bash
sudo systemctl list-timers divvy-auto-update.timer
sudo journalctl -u divvy-auto-update.service -n 100 --no-pager
sudo systemctl start divvy-auto-update.service
```

To pause updates, run `sudo systemctl disable --now divvy-auto-update.timer`. To uninstall, also stop the service and remove only the installed updater files:

```bash
sudo systemctl stop divvy-auto-update.service
sudo rm -f /etc/systemd/system/divvy-auto-update.service \
  /etc/systemd/system/divvy-auto-update.timer \
  /etc/systemd/system/divvy-auto-update.service.d/user.conf \
  /usr/local/libexec/divvy-auto-update /etc/divvy/auto-update.env
sudo systemctl daemon-reload
```

The service executes trusted code from main with Docker access. Keep backups and restrict who can merge there. If the updater scripts or unit files change later, rerun the installer to replace the installed copies.


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
| **Android release** (`.github/workflows/android-release.yml`) | `v*` tags; pushes to `main` touching the app or build scripts; manual trigger | Builds a signed release APK without the API key, checks its signature, then publishes a release for the tag, or replaces the `latest-main` pre-release | not measured yet |

To run the iOS build by hand, use **Actions > iOS > Run workflow**, or run `gh workflow run ios.yml`.

### Releases

To publish a version, tag a commit on `main` and push the tag:

```bash
git tag v1.2.0
git push origin v1.2.0
```

The release is named after the tag, with notes generated from the merged PRs. `versionName` is the tag without the `v`, and `versionCode` is the number of commits up to it, so later builds always install over earlier ones.

The workflow needs these repository settings:

| Name | Kind | Value |
|------|------|-------|
| `ANDROID_KEYSTORE_BASE64` | secret | The release keystore, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | secret | Keystore password |
| `ANDROID_KEY_ALIAS` | secret | Key alias in the keystore |
| `ANDROID_KEY_PASSWORD` | secret | Key password |
| `DIVVY_API_BASE_URL` | variable | The server address built into the APK |

**Keep a backup of the keystore and its password outside GitHub.** Secrets can't be read back. If the key is lost, installed apps can't be updated, and everyone has to uninstall (losing the app's settings) and install again.

To build a signed release locally, set `DIVVY_SIGNING_STORE_FILE`, `DIVVY_SIGNING_STORE_PASSWORD`, `DIVVY_SIGNING_KEY_ALIAS` and `DIVVY_SIGNING_KEY_PASSWORD`, then run `./gradlew :androidApp:assembleRelease "-Pdivvy.apiKey="`. Without the `DIVVY_SIGNING_*` variables the release APK is unsigned.
