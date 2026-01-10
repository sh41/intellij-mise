package com.github.l34130.mise.core.command

import com.github.l34130.mise.core.MiseTomlFileVfsListener
import com.github.l34130.mise.core.cache.MiseCacheService
import com.github.l34130.mise.core.setting.MiseExecutableManager
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.application


/**
 * Smart cache for mise commands with broadcast-based invalidation and proactive warming.
 *
 * Cache is invalidated when:
 * - Any mise config file changes (via MiseTomlFileVfsListener.MISE_TOML_CHANGED)
 * - Mise executable changes (via MiseExecutableManager.MISE_EXECUTABLE_CHANGED)
 *
 * After invalidation, commonly-used commands (env, ls) are proactively re-warmed in background
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
     * Proactively warm commonly-used commands in background after cache invalidation.
     * This prevents EDT blocking when UI components (tool window, etc.) refresh.
     */
    private fun warmCommonCommands() {
        application.executeOnPooledThread {
            try {
                logger.debug("Warming command cache for commonly-used commands")
                val workDir = project.guessMiseProjectPath()
                val configEnvironment = project.service<com.github.l34130.mise.core.setting.MiseProjectSettings>().state.miseConfigEnvironment
                
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
     */
    fun <T> getCached(
        key: String,
        compute: () -> Result<T>
    ): Result<T> {
        val entry = cacheService.getCachedCommand(key) {
            CacheEntry(compute())
        }

        @Suppress("UNCHECKED_CAST")
        return entry.value as Result<T>
    }

    // === Data Classes ===

    private data class CacheEntry(
        val value: Result<*>
    )
}
