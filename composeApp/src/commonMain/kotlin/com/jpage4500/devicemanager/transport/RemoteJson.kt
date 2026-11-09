package com.jpage4500.devicemanager.transport

import kotlinx.serialization.json.*

/**
 * reading a Device Manager server's JSON defensively, for the two websockets that share it.
 *
 * Hand-read rather than `@Serializable`, for the same reason [RemoteHttpTransport] reads its bodies
 * that way: the shape belongs to the Swing app, where it is Gson writing whatever a map happened to
 * hold. A field that changes type there must come back null here, not end a log stream.
 */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

/** a nested object read as strings, skipping any value that isn't one. */
internal fun JsonObject.stringMap(key: String): Map<String, String> =
    (this[key] as? JsonObject)
        ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
        ?.toMap()
        .orEmpty()

/** the whole text frame as an object, or null if it isn't one. */
internal fun jsonObjectOrNull(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
