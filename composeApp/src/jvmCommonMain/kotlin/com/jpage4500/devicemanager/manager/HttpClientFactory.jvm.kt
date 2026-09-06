package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.util.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val log = LoggerConfig.createLogger("HttpClient")

// shared across every client this factory builds, so evictIdleConnections() can reach the sockets.
// without an explicit instance each client builds its own pool and nothing can reach them
private val connectionPool = ConnectionPool()

// the IO dispatcher is required rather than tidy: evictAll() closes each socket, and closing a TLS
// socket drains its outgoing queue — StrictMode counts that as network on the main thread
actual suspend fun evictIdleConnections() = withContext(ioDispatcher) {
    log.v("evictIdleConnections: dropping idle keep-alive sockets")
    try {
        connectionPool.evictAll()
    } catch (e: Exception) {
        log.w("evictIdleConnections: exception: ${e.message}")
    }
}

actual fun createHttpClient(useCookies: Boolean): HttpClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // accepts any server certificate. the LAN routes this app takes are a Device Manager instance
    // or an adb server on someone's desk, neither of which has a CA-signed certificate — see
    // docs/ADB-TRANSPORTS.md. that is a trusted network only, which port 5037 already required
    @Suppress("CustomX509TrustManager")
    val trustManager = object : X509TrustManager {
        @Suppress("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        @Suppress("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
                // okhttp's Dispatcher allows 5 concurrent requests per host, and every screenshot
                // poll for every mirrored device shares this one client. 45 devices is the workload
                // to protect, so a fleet refresh must not park a user's tap behind four others
                dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
                connectionPool(connectionPool)
                // keepalive ping for a websocket. MUST be on the engine: the OkHttp engine ignores
                // install(WebSockets){pingInterval} and pings only off OkHttpClient.pingInterval.
                // without it a half-open socket never errors, so a reconnect loop never fires
                pingInterval(30, TimeUnit.SECONDS)
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

        install(WebSockets)
    }
}
