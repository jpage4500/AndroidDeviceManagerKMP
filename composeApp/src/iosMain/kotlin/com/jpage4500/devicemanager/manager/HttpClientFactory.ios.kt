package com.jpage4500.devicemanager.manager

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.*
import platform.Foundation.*
import kotlin.time.Duration.Companion.seconds

/**
 * a no-op on iOS: NSURLSession owns its connection cache and exposes no "drop idle sockets" call —
 * `URLSession.reset()` would also wipe cookies and credentials. iOS also suspends rather than dozes
 * and tears its own connections down when it does, so the half-open-socket stall this exists for
 * doesn't reproduce here.
 */
actual suspend fun evictIdleConnections() {}

@OptIn(ExperimentalForeignApi::class)
actual fun createHttpClient(useCookies: Boolean): HttpClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    return HttpClient(Darwin) {
        engine {
            // NSURLSession allows 6 connections per host by default; every screenshot poll shares
            // this one client, so match the 20 the JVM engine is set to
            configureSession {
                HTTPMaximumConnectionsPerHost = 20
            }

            // accept any server certificate — see the note in HttpClientFactory.jvm.kt: the LAN
            // routes this app takes have no CA-signed certificate to check
            handleChallenge { _, _, challenge, completionHandler ->
                val trust = challenge.protectionSpace.serverTrust
                if (trust != null) {
                    completionHandler(0, NSURLCredential.credentialForTrust(trust))
                } else {
                    completionHandler(0, null)
                }
            }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000L
            socketTimeoutMillis = 20_000L
            connectTimeoutMillis = 10_000L
        }

        install(ContentNegotiation) { json(json) }

        // the one place all http traffic is logged — filter a log by the "HttpLog" tag
        installHttpLog()

        if (useCookies) {
            install(HttpCookies) { storage = appCookieStorage }
        }

        install(WebSockets) {
            // surface a dead socket as an error so a reconnect loop can fire
            pingInterval = 30.seconds
        }
    }
}
