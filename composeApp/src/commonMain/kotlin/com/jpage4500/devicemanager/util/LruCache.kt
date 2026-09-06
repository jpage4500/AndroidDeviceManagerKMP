package com.jpage4500.devicemanager.util

/**
 * simple LRU (Least Recently Used) cache implementation
 * for multiplatform use
 */
class LruCache<K, V>(private val maxSize: Int) {
    private val cache = LinkedHashMap<K, V>()

    /**
     * get value from cache
     * @return value or null if not found
     */
    operator fun get(key: K): V? {
        return cache[key]
    }

    /**
     * put value in cache
     * if cache exceeds maxSize, removes oldest entry
     */
    fun put(key: K, value: V) {
        // remove if already exists (to re-add at end)
        cache.remove(key)
        cache[key] = value

        // remove oldest entry if cache is full
        if (cache.size > maxSize) {
            val iterator = cache.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    /**
     * remove value from cache
     */
    fun remove(key: K): V? {
        return cache.remove(key)
    }

    /**
     * check if key exists in cache
     */
    fun containsKey(key: K): Boolean {
        return cache.containsKey(key)
    }

    /**
     * clear all entries from cache
     */
    fun clear() {
        cache.clear()
    }

    /**
     * get current cache size
     */
    fun size(): Int {
        return cache.size
    }
}

