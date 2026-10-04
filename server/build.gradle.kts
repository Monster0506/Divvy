plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.monster0506.divvy"
version = "1.0"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.client.core.jvm)
    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.ktor.client.content.negotiation.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock.jvm)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.testcontainers.postgresql)
}

// Flyway discovers its plugins through META-INF/services; without merging, the fat jar keeps only one copy of that file.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    // The Ktor plugin sets EXCLUDE, which drops duplicate service files before the merge sees them
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
