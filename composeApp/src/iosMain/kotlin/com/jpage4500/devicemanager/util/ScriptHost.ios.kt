package com.jpage4500.devicemanager.util

// an iPhone has no host shell to run scripts from; the Scripts page doesn't appear
actual object ScriptHost {
    actual val supported: Boolean = false
    actual fun folder(): String? = null
    actual fun list(): List<Script> = emptyList()
    actual suspend fun run(script: Script, args: List<String>, onLine: (String) -> Unit): Int? = null
    actual fun create(name: String): Script? = null
    actual fun edit(script: Script): Boolean = false
}
