package com.jpage4500.devicemanager.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * utility class for JSON operations
 */
object JsonUtils {
    val log = LoggerConfig.createLogger("JsonUtils")

    /**
     * default JSON instance for parsing
     */
    @OptIn(ExperimentalSerializationApi::class)
    val defaultJson = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    /**
     * pretty print JSON instance
     */
    @OptIn(ExperimentalSerializationApi::class)
    val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    /**
     * helper function to decode JSON with built-in exception handling
     * @param jsonString the JSON string to decode
     * @param serializer the serializer for type T
     * @return object of type T, re-created from JSON string, or null if parsing fails
     */
    fun <T> fromJson(jsonString: String?, serializer: KSerializer<T>): T? {
        if (jsonString.isNullOrEmpty()) return null
        return try {
            defaultJson.decodeFromString(serializer, jsonString)
        } catch (e: Exception) {
            log.e { "fromJson: Exception: ${e.message}" }
            null
        }
    }

    /**
     * helper function to decode JSON with built-in exception handling (reified version)
     * @return object of type T, re-created from JSON string, or null if parsing fails
     */
    inline fun <reified T> fromJson(json: String?): T? {
        if (json.isNullOrEmpty()) return null
        return try {
            defaultJson.decodeFromString<T>(json)
        } catch (e: Exception) {
            log.e { "fromJson: Exception: ${e.message}" }
            log.v { e.stackTraceToString() }
            null
        }
    }

    /**
     * helper function to encode object to JSON with built-in exception handling
     * @param obj the object to encode
     * @param prettyPrint whether to format the JSON for readability
     * @return JSON string representation of object, or null if encoding fails
     */
    inline fun <reified T> toJson(obj: T?, prettyPrint: Boolean = false): String? {
        if (obj == null) return null
        return try {
            val json = if (prettyPrint) prettyJson else defaultJson
            json.encodeToString<T>(obj)
        } catch (e: Exception) {
            log.e { "toJson: Exception: ${e.message}" }
            null
        }
    }

    /**
     * decode JSON string to a list, returning empty list on failure
     * @param json the JSON string to decode
     * @return list of type T, or empty list if parsing fails
     */
    inline fun <reified T> stringToList(json: String?): List<T> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            defaultJson.decodeFromString<List<T>>(json)
        } catch (e: Exception) {
            log.e { "stringToList: Exception: ${e.message}, json:$json" }
            log.v { e.stackTraceToString() }
            emptyList()
        }
    }

    /**
     * decode JSON string to a map, returning empty map on failure
     * @param json the JSON string to decode
     * @return map of type Map<String, T>, or empty map if parsing fails
     */
    inline fun <reified T> stringToMap(json: String?): Map<String, T> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            defaultJson.decodeFromString<Map<String, T>>(json)
        } catch (e: Exception) {
            log.e { "stringToMap: Exception: ${e.message}, json:$json" }
            log.v { e.stackTraceToString() }
            emptyMap()
        }
    }
}
