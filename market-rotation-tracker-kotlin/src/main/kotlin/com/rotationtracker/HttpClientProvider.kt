package com.rotationtracker

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.util.concurrent.TimeUnit

/** Single shared Ktor client — thread-safe and reusable across all services. */
val sharedClient: HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(15, TimeUnit.SECONDS)
        }
    }
}
