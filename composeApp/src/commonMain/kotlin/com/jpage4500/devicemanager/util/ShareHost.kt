package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.manager.*

/**
 * the listening half of Share Devices: a socket on this machine that other copies of the app read
 * its devices through.
 *
 * Desktop only, like [ScrcpyHost]. The phone is the *client* of this — it reaches a desk's whole USB
 * fleet over [com.jpage4500.devicemanager.transport.RemoteHttpTransport] — and a phone sharing its
 * own wireless devices onward is not a case anyone has. So [supported] is false there and the Share
 * Devices button doesn't appear. What the server *does* lives with the listener in desktopMain;
 * [ShareServer] is the state the dialog reads. See docs/SHARE-SERVER.md.
 */
expect object ShareHost {
    val supported: Boolean

    /**
     * start listening on every interface at [port].
     *
     * @return null once it is listening, or why it couldn't — "Address already in use", usually
     */
    suspend fun start(port: Int): String?

    /** stop listening and drop every open connection, websockets included. */
    suspend fun stop()

    /** the addresses a client could reach this machine at, LAN ones first. Slow — reverse DNS. */
    suspend fun networks(): List<LocalNetwork>

    /** what this computer calls itself, for a server nobody has named. Slow on a Mac with no DNS. */
    suspend fun hostName(): String?
}
