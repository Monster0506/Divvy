package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.AppConfig
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class MlClientFactoryTest {
    private fun config(mlUrl: String?) = AppConfig(
        port = 0,
        database = AppConfig.DatabaseSettings("jdbc:postgresql://db/divvy", "u", "p"),
        mlServiceUrl = mlUrl,
    )

    @Test
    fun `no url means the noop client`() {
        assertSame(NoopMlClient, createMlClient(config(null)))
        assertSame(NoopMlClient, createMlClient(config("   ")))
    }

    @Test
    fun `a url means a real client`() {
        val client = createMlClient(config("http://ml:8000"))

        assertIs<KtorMlClient>(client)
        client.close()
    }
}
