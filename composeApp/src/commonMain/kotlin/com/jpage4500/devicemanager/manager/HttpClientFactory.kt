package com.jpage4500.devicemanager.manager

import io.ktor.client.*
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import kotlin.concurrent.Volatile

/**
 * the app's http client, built once per platform.
 *
 * every client this makes logs through [HttpLog], so filtering a log by that one tag shows the whole
 * network conversation. self-signed certificates are accepted: the LAN routes this app takes — an
 * adb server on another machine, a Device Manager instance serving its HTTP API — are not going to
 * have a certificate a public CA signed. See docs/ADB-TRANSPORTS.md for what those routes reach.
 *
 * @param useCookies install [HttpCookies] for a shared session cookie. off for anything talking to
 *   a service we don't own, so a malformed Set-Cookie can't break the response pipeline.
 */
expect fun createHttpClient(useCookies: Boolean = true): HttpClient

/**
 * drop every idle keep-alive connection so the next request opens a fresh socket.
 *
 * call it when the app comes back to the foreground. while backgrounded — android doze, a closed
 * laptop lid — the wifi link drops and re-associates without the other end ever getting to send a
 * FIN, so the pooled socket is half-open: it still looks healthy to the pre-reuse check, the request
 * is written to it, and nothing comes back until TCP exhausts its retransmit backoff ~14s later.
 * evicting first turns that 14s first request into a normal one.
 *
 * only closes *idle* connections; anything with a call in flight is left alone. suspending and off
 * the UI thread by contract — closing a TLS socket drains its outgoing queue, which is a real
 * network write, and android's StrictMode turns that into a NetworkOnMainThreadException. never
 * throws: whatever the caller does next matters more than the eviction.
 */
expect suspend fun evictIdleConnections()

/**
 * shared cookie storage. wraps AcceptAllCookiesStorage so it can be reset — ktor's own storage has
 * no public clear.
 */
val appCookieStorage = ClearableCookiesStorage()

class ClearableCookiesStorage : CookiesStorage {
    @Volatile
    private var inner: CookiesStorage = AcceptAllCookiesStorage()

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = inner.addCookie(requestUrl, cookie)
    override suspend fun get(requestUrl: Url): List<Cookie> = inner.get(requestUrl)
    override fun close() = inner.close()

    fun clearAll() {
        inner = AcceptAllCookiesStorage()
    }
}
