package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*

// Dispatchers.IO doesn't exist on native; Default is backed by a real thread pool there, and the
// socket work is all suspending rather than blocking, so it doesn't starve it
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
