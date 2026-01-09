package com.github.l34130.mise.core.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generic cache with stampede protection using per-key mutexes.
 *
 * Prevents multiple concurrent computations of the same key by using a double-check
 * locking pattern with per-key mutexes.
 *
 * Usage:
 * ```kotlin
 * private val cache = StampedeProtectedCache<String, Result<String>>()
 *
 * suspend fun getValue(key: String): Result<String> {
 *     return cache.get(key) {
 *         // Expensive computation here
 *         expensiveOperation()
 *     }
 * }
 * ```
 *
 * @param V The type of cached values
 */
class StampedeProtectedCache<K : Any, V : Any> {
    private val cache = mutableMapOf<K, V>()
    private val cacheMutex = Mutex()

    // Per-key mutexes to prevent stampede
    private val computationLocks = mutableMapOf<K, Mutex>()
    private val locksMapMutex = Mutex()

    /**
     * Get cached value or compute it if not present or invalid.
     *
     * @param key Cache key
     * @param isValid Optional validation function. If provided and returns false, cache is bypassed.
     * @param compute Computation function to run on cache miss
     * @return Computed or cached value
     */
    suspend fun get(
        key: K,
        isValid: (suspend (V) -> Boolean)? = null,
        compute: suspend () -> V
    ): V {
        // Check cache and validate
        val cached = cacheMutex.withLock { cache[key] }
        if (cached != null && (isValid == null || isValid(cached))) {
            return cached
        }

        // Get or create a mutex for this key
        val mutex = locksMapMutex.withLock {
            computationLocks.getOrPut(key) { Mutex() }
        }

        // Lock for this specific key to prevent stampede
        return mutex.withLock {
            // Double-check cache after acquiring lock (another coroutine might have computed it)
            val recheck = cacheMutex.withLock { cache[key] }
            if (recheck != null && (isValid == null || isValid(recheck))) {
                return@withLock recheck
            }

            // Cache miss or invalid - compute value
            val result = compute()

            // Store in cache
            cacheMutex.withLock {
                cache[key] = result
            }

            // Clean up mutex after computation
            locksMapMutex.withLock {
                computationLocks.remove(key)
            }

            result
        }
    }

    /**
     * Get value if present in cache without validation.
     */
    suspend fun getIfPresent(key: K): V? {
        return cacheMutex.withLock { cache[key] }
    }

    /**
     * Invalidate a specific cache entry.
     */
    suspend fun invalidate(key: K) {
        cacheMutex.withLock {
            cache.remove(key)
        }
    }

    /**
     * Invalidate all cache entries.
     */
    suspend fun invalidateAll() {
        cacheMutex.withLock {
            cache.clear()
        }
    }
}
