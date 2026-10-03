package com.monster0506.divvy.server

import io.ktor.server.testing.testApplication
import kotlin.test.Test

class ApplicationTest {
    @Test
    fun moduleStarts() = testApplication {
        application { module() }
        startApplication()
    }
}
