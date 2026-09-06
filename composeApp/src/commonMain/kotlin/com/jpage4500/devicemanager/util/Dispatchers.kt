package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*

/**
 * where blocking work goes.
 *
 * the adb client is the only thing in the app that waits on a socket, and every one of its calls is
 * a suspend function that hops here first — nothing it does may reach the UI thread.
 */
expect val ioDispatcher: CoroutineDispatcher
