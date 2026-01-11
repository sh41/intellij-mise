package com.github.l34130.mise.core.command

import com.github.l34130.mise.core.MiseTomlFileVfsListener
import com.github.l34130.mise.core.cache.MiseCacheService
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext


/**
 * Smart cache for mise commands with broadcast-based invalidation and proactive warming.
 *
 * Cache is invalidated when:
 * - Any mise config file changes (via MiseTomlFileVfsListener.MISE_TOML_CHANGED)
 * - Mise executable changes (via MiseExecutableManager.MISE_EXECUTABLE_CHANGED)
 *
 * After invalidation, commonly used commands (env, ls) are proactively re-warmed in background
 * to avoid EDT blocking on next access.
 *
 * Usage:
 * ```kotlin
 * cache.getCached(key = "env:$workDir:$configEnvironment") {
 *     // Computation to run on cache miss
 *     miseCommandLine.runCommandLine<Map<String, String>>(listOf("env", "--json"))
 * }
 * ```
 */
@Service(Service.Level.PROJECT)
class MiseCommandCache(private val project: Project) {
    private val logger = logger<MiseCommandCache>()
    private val cacheService = project.service<MiseCacheService>()

    init {
        val connection = project.messageBus.connect()

        // Subscribe to mise config file changes
        connection.subscribe(
            MiseTomlFileVfsListener.MISE_TOML_CHANGED,
            Runnable {
                logger.info("Mise config changed, invalidating entire cache")
                cacheService.invalidateAllCommands()
                warmCommonCommands()
            }
        )

        // Subscribe to mise executable changes
        connection.subscribe(
            MiseExecutableManager.MISE_EXECUTABLE_CHANGED,
            Runnable {
                logger.info("Mise executable changed, invalidating entire cache")
                cacheService.invalidateAllCommands()
                warmCommonCommands()
            }
        )
    }

    /**
     * Proactively warm commonly used commands in background after cache invalidation.
     * This prevents EDT blocking when UI components (tool window, etc.) refresh.
     */
    private fun warmCommonCommands() {
        application.executeOnPooledThread {
            try {
                logger.debug("Warming command cache for commonly-used commands")
                val workDir = project.guessMiseProjectPath()
                val configEnvironment = project.service<com.github.l34130.mise.core.setting.MiseProjectSettings>().state.miseConfigEnvironment

                // Warm the version cache
                MiseCommandLineHelper.getMiseVersion(project, workDir)
                // Warm env vars (used by env customizers, tool window, etc.)
                MiseCommandLineHelper.getEnvVars(project, workDir, configEnvironment)

                // Warm dev tools (used by tool window, SDK setup, etc.)
                MiseCommandLineHelper.getDevTools(project, workDir, configEnvironment)

                logger.debug("Command cache warmed successfully")
            } catch (e: Exception) {
                logger.warn("Failed to warm command cache after invalidation", e)
            }
        }
    }

    /**
     * Get the cached value or compute it.
     * Uses Caffeine's built-in stampede protection.
     * Internal method - callers should use getCachedWithProgress for threading protection.
     */
    private fun <T> getCached(
        cacheKey: MiseCacheKey<T>,
        compute: () -> T
    ): T {
        val entry = cacheService.getCachedCommand(cacheKey.key) {
            CacheEntry(compute() as Any)
        }

        @Suppress("UNCHECKED_CAST")
        return entry.value as T
    }

    /**
     * Get the cached value if present, without computing.
     * Returns null if the key is not cached.
     * This is a fast, synchronous operation suitable for fast-path optimization.
     * Internal method - callers should use getCachedWithProgress for threading protection.
     */
    private fun <T> getIfCached(cacheKey: MiseCacheKey<T>): T? {
        val entry = cacheService.getIfCachedCommand<CacheEntry>(cacheKey.key)
        @Suppress("UNCHECKED_CAST")
        return entry?.value as? T
    }

    /**
     * Get cached value with automatic fast-path optimization and threading protection.
     *
     * - Fast path: Returns cached result synchronously (instant, no thread switching)
     * - Slow path: Executes compute() on appropriate thread with progress indicator
     *
     * Thread-safe: Can be called from EDT, background thread, or read-action thread.
     * The cache automatically handles threading based on calling context.
     *
     * Type-safe: Uses sealed class MiseCacheKey to guarantee key→type mapping at compile time.
     *
     * @param cacheKey Type-safe cache key (contains key string + progress title + type information)
     * @param compute Function to compute value on cache miss (must be thread-safe)
     * @return Cached or computed value of type T
     */
    fun <T> getCachedWithProgress(
        cacheKey: MiseCacheKey<T>,
        compute: () -> T
    ): T {
        // Fast path: check cache synchronously (instant, no threading overhead)
        getIfCached(cacheKey)?.let {
            logger.debug("Cache hit for key: ${cacheKey.key}")
            return it
        }

        logger.debug("Cache miss for key: ${cacheKey.key}, loading with progress")

        // Slow path: execute with appropriate threading strategy
        return when {
            application.isDispatchThread -> {
                logger.debug("EDT detected, using modal progress")
                runWithModalProgressBlocking(project, cacheKey.progressTitle) {
                    getCached(cacheKey, compute)
                }
            }
            !application.isReadAccessAllowed -> {
                logger.debug("No read lock, dispatching to EDT")
                var result: T? = null
                application.invokeAndWait {
                    runWithModalProgressBlocking(project, cacheKey.progressTitle) {
                        result = getCached(cacheKey, compute)
                    }
                }
                result ?: throw ProcessCanceledException()
            }
            else -> {
                logger.debug("Background thread with read lock, using background progress")
                runBlocking {
                    withBackgroundProgress(project, cacheKey.progressTitle) {
                        withContext(Dispatchers.IO) {
                            getCached(cacheKey, compute)
                        }
                    }
                }
            }
        }
    }

    // === Data Classes ===

    private data class CacheEntry(
        val value: Any
    )
}
