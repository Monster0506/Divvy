import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Base URL and API key, first found of: the git-ignored local.properties, -Pdivvy.* or a gradle.properties, a dev default.
// local.properties wins so a phone build can point at a deployed server without touching tracked files.
val localProperties = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) } }
    .orElse(Properties())

fun apiSetting(name: String, default: String): Provider<String> =
    localProperties.map { it.getProperty(name).orEmpty().trim() }
        .filter { it.isNotEmpty() }
        .orElse(providers.gradleProperty(name))
        .orElse(default)

val generateApiConfig = tasks.register("generateApiConfig") {
    val baseUrl = apiSetting("divvy.apiBaseUrl", "http://localhost:8080")
    val apiKey = apiSetting("divvy.apiKey", "dev-key")
    val outDir = layout.buildDirectory.dir("generated/apiConfig")
    inputs.property("baseUrl", baseUrl)
    inputs.property("apiKey", apiKey)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/monster0506/divvy/data/api/BuildApiConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.monster0506.divvy.data.api
            |
            |internal object BuildApiConfig {
            |    const val BASE_URL = "${baseUrl.get().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}"
            |    const val API_KEY = "${apiKey.get().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    android {
        namespace = "com.monster0506.divvy.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTest {}
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "com.monster0506.divvy.shared")
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "composeApp"
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain { kotlin.srcDir(generateApiConfig) }
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")
            implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.multiplatform.get()}")
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.serialization.json)
            implementation("org.jetbrains.compose.components:components-resources:${libs.versions.compose.multiplatform.get()}")
            implementation("org.jetbrains.compose.ui:ui:${libs.versions.compose.multiplatform.get()}")
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:${libs.versions.compose.multiplatform.get()}")
            implementation(libs.multiplatform.settings.no.arg)
            implementation(libs.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath("org.jetbrains.compose.ui:ui-tooling:${libs.versions.compose.multiplatform.get()}")
}