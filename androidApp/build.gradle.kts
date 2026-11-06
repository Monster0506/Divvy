plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Release builds are signed with the key in DIVVY_SIGNING_* (set by the release workflow from GitHub secrets).
// Without them a release build is left unsigned.
fun signingEnv(name: String): String? = providers.environmentVariable("DIVVY_SIGNING_$name").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.monster0506.divvy"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.monster0506.divvy"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // The release workflow passes these; each build's code must be higher than the last one installed.
        versionCode = providers.gradleProperty("divvy.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("divvy.versionName").orNull ?: "dev"
        manifestPlaceholders["appName"] = "Divvy"
    }
    signingConfigs {
        signingEnv("STORE_FILE")?.let { storePath ->
            create("release") {
                storeFile = file(storePath)
                storePassword = requireNotNull(signingEnv("STORE_PASSWORD")) { "DIVVY_SIGNING_STORE_PASSWORD is not set" }
                keyAlias = requireNotNull(signingEnv("KEY_ALIAS")) { "DIVVY_SIGNING_KEY_ALIAS is not set" }
                keyPassword = requireNotNull(signingEnv("KEY_PASSWORD")) { "DIVVY_SIGNING_KEY_PASSWORD is not set" }
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
        // A separate app, so a dev build installs next to a release one (they are signed with different keys).
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "Divvy Dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
}