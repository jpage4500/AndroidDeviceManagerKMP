package com.jpage4500.devicemanager.util

import android.annotation.SuppressLint
import android.content.Context

/**
 * the application context, for the handful of android APIs that need one and can't be handed one.
 *
 * set from `MainActivity.onCreate` before anything else runs. holds the *application* context, not
 * the activity's, so it outlives a rotation and leaks nothing.
 */
@SuppressLint("StaticFieldLeak")
object AndroidContextProvider {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
