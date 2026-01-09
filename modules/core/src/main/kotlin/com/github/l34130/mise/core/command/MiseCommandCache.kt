package com.github.l34130.mise.core.command

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.l34130.mise.core.MiseTomlFileVfsListener
import com.github.l34130.mise.core.setting.MiseExecutableManager
import com.github.l34130.mise.core.util.StampedeProtectedCache
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking


/**
 * Smart cache for mise commands using Caffeine with broadcast-based invalidation.
 *
 * Cache is invalidated when:
 * - Any mise config file changes (via MiseTomlFileVfsListener.MISE_TOML_CHANGED)
 * - Mise executable changes (via MiseExecutableManager.MISE_EXECUTABLE_CHANGED)
 *
 * Usage:
 * ```kotlin
 * cache.getCachedBlocking(key = "env:$workDir:$configEnvironment") {
 *     // Computation to run on cache miss
 *     miseCommandLine.runCommandLine<Map<String, String>>(listOf("env", "--json"))
 * }
 * ```
 */
@Service(Service.Level.PROJECT)
class MiseCommandCache(project: Project) {
    private val logger = logger<MiseCommandCache>()

    // Caffeine cache with size-based eviction
    private val cache = Caffeine.newBuilder()
        .maximumSize(500)
        .removalListener<String, CacheEntry> { key: String?, _: CacheEntry?, cause ->
            if (cause == RemovalCause.SIZE) {
                logger.debug("Cache entry evicted due to size limit: $key")
            }
        }
        .build<String, CacheEntry>()

    // Stampede-protected computation cache
    private val stampedeCache = StampedeProtectedCache<String, CacheEntry>()

    init {
        val connection = project.messageBus.connect()

        // Subscribe to mise config file changes
        connection.subscribe(
            MiseTomlFileVfsListener.MISE_TOML_CHANGED,
            Runnable {
                logger.info("Mise config changed, invalidating entire cache")
                cache.invalidateAll()
                runBlocking {
                    stampedeCache.invalidateAll()
                }
            }
        )

        // Subscribe to mise executable changes
        connection.subscribe(
            MiseExecutableManager.MISE_EXECUTABLE_CHANGED,
            Runnable {
                logger.info("Mise executable changed, invalidating entire cache")
                cache.invalidateAll()
                runBlocking {
                    stampedeCache.invalidateAll()
                }
            }
        )
    }

    /**
     * Get cached value or compute it (synchronous version).
     */
    fun <T> getCachedBlocking(
        key: String,
        compute: () -> Result<T>
    ): Result<T> = runBlocking {
        getCached(key) { compute() }
    }

    /**
     * Get cached value or compute it (coroutine version).
     */
    suspend fun <T> getCached(
        key: String,
        compute: suspend () -> Result<T>
    ): Result<T> {
        // Use stampede-protected cache (no validation needed - invalidation is broadcast-based)
        val entry = stampedeCache.get(key) {
            logger.debug("Cache miss: $key")
            val result = compute()
            val entry = CacheEntry(result)

            // Also store in Caffeine cache for size-based eviction
            result.onSuccess {
                cache.put(key, entry)
                logger.debug("Cached: $key")
            }

            entry
        }

        @Suppress("UNCHECKED_CAST")
        return entry.value as Result<T>
    }

    // === Data Classes ===

    private data class CacheEntry(
        val value: Result<*>
    )
}
