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
./gradlew :server:run
```

The Ktor server listens on port 8080.

### Web (Wasm)

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

This starts a dev server and opens the app in your browser.

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
