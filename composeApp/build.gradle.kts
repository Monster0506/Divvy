import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Base URL and API key come from -Pdivvy.apiBaseUrl / -Pdivvy.apiKey or gradle.properties.
val generateApiConfig = tasks.register("generateApiConfig") {
    val baseUrl = providers.gradleProperty("divvy.apiBaseUrl").orElse("http://localhost:8080")
    val apiKey = providers.gradleProperty("divvy.apiKey").orElse("dev-key")
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
            |    const val BASE_URL = "${baseUrl.get()}"
            |    const val API_KEY = "${apiKey.get()}"
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
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath("org.jetbrains.compose.ui:ui-tooling:${libs.versions.compose.multiplatform.get()}")
}