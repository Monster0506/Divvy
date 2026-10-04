package com.monster0506.divvy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.monster0506.divvy.data.api.ApiConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The Android emulator reaches services on the host at 10.0.2.2, not localhost.
        val config = ApiConfig()
        val baseUrl = if (config.baseUrl == "http://localhost:8080") "http://10.0.2.2:8080" else config.baseUrl
        setContent { App(config.copy(baseUrl = baseUrl)) }
    }
}
