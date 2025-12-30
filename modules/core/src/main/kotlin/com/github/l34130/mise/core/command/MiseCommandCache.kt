package com.github.l34130.mise.core.command

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.l34130.mise.core.MiseConfigFileResolver
import com.github.l34130.mise.core.setting.MiseExecutableManager
import com.github.l34130.mise.core.util.StampedeProtectedCache
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.runBlocking

/**
 * Invalidation strategy for cached commands.
 */
enum class CacheInvalidation {
    /** Invalidate when mise executable changes */
    ON_EXECUTABLE_CHANGE,

    /** Invalidate when mise config files change */
    ON_CONFIG_CHANGE,

    /** Never invalidate (cache forever, until IDE restart) */
    NEVER
}

/**
 * Smart cache for mise commands using Caffeine with file-based invalidation.
 *
 * Usage:
 * ```kotlin
 * cache.getCachedBlocking(
 *     key = "env:$workDir:$configEnvironment",
 *     invalidation = CacheInvalidation.ON_CONFIG_CHANGE,
 *     workDir = workDir,
 *     configEnvironment = configEnvironment
 * ) {
 *     // Computation to run on cache miss
 *     miseCommandLine.runCommandLine<Map<String, String>>(listOf("env", "--json"))
 * }
 * ```
 */
@Service(Service.Level.PROJECT)
class MiseCommandCache(private val project: Project) {
    private val logger = logger<MiseCommandCache>()
    private val configResolver = project.service<MiseConfigFileResolver>()
    private val executableManager = project.service<MiseExecutableManager>()

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
        // Listen to file system changes
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach { event ->
                        handleFileEvent(event)
                    }
                }
            }
        )
    }

    /**
     * Get cached value or compute it (synchronous version).
     */
    fun <T> getCachedBlocking(
        key: String,
        invalidation: CacheInvalidation,
        workDir: String = project.guessMiseProjectPath(),
        configEnvironment: String? = null,
        compute: () -> Result<T>
    ): Result<T> = runBlocking {
        getCached(key, invalidation, workDir, configEnvironment) { compute() }
    }

    /**
     * Get cached value or compute it (coroutine version).
     */
    suspend fun <T> getCached(
        key: String,
        invalidation: CacheInvalidation,
        workDir: String = project.guessMiseProjectPath(),
        configEnvironment: String? = null,
        compute: suspend () -> Result<T>
    ): Result<T> {
        // Use stampede-protected cache
        val entry = stampedeCache.get(
            key = key,
            isValid = { cachedEntry ->
                isCacheValid(cachedEntry, workDir, configEnvironment)
            }
        ) {
            // Cache miss or invalid - compute value
            logger.debug("Cache miss or invalid: $key")
            val result = compute()

            // Create cache entry with metadata
            val metadata = when (invalidation) {
                CacheInvalidation.ON_EXECUTABLE_CHANGE -> {
                    val execPath = executableManager.getExecutablePath(workDir)
                    CacheMetadata.ExecutableDependent(
                        executablePath = execPath,
                        executableTimestamp = getFileTimestamp(execPath)
                    )
                }

                CacheInvalidation.ON_CONFIG_CHANGE -> {
                    val configFiles = getConfigFiles(workDir, configEnvironment)
                    CacheMetadata.ConfigDependent(
                        configTimestamps = configFiles.associateWith { it.timeStamp }
                    )
                }

                CacheInvalidation.NEVER -> {
                    CacheMetadata.Permanent
                }
            }

            val entry = CacheEntry(result, metadata)

            // Also store in Caffeine cache for size-based eviction
            result.onSuccess {
                cache.put(key, entry)
                logger.debug("Cached: $key (invalidation=$invalidation)")
            }

            entry
        }

        @Suppress("UNCHECKED_CAST")
        return entry.value as Result<T>
    }

    // === Private Helper Methods ===

    private suspend fun isCacheValid(
        entry: CacheEntry,
        workDir: String,
        configEnvironment: String?
    ): Boolean {
        return when (val metadata = entry.metadata) {
            is CacheMetadata.ExecutableDependent -> {
                // Check if executable changed
                val currentPath = executableManager.getExecutablePath(workDir)
                val currentTimestamp = getFileTimestamp(currentPath)
                currentTimestamp == metadata.executableTimestamp
            }

            is CacheMetadata.ConfigDependent -> {
                // Check if any config files changed
                val configFiles = getConfigFiles(workDir, configEnvironment)
                val currentTimestamps = configFiles.associateWith { it.timeStamp }
                currentTimestamps == metadata.configTimestamps
            }

            is CacheMetadata.Permanent -> {
                true  // Never invalidate
            }
        }
    }

    private fun handleFileEvent(event: VFileEvent) {
        val file = event.file ?: return

        when (event) {
            is VFileContentChangeEvent,
            is VFileCreateEvent,
            is VFileDeleteEvent -> {
                // Check if any cached entry is watching this file
                val affectedEntries = cache.asMap().entries.filter { (_, entry) ->
                    when (val metadata = entry.metadata) {
                        is CacheMetadata.ConfigDependent -> {
                            metadata.configTimestamps.keys.contains(file)
                        }
                        is CacheMetadata.ExecutableDependent -> {
                            // Check if this file matches the stored executable path in metadata
                            file.path == metadata.executablePath || file.path.contains(metadata.executablePath)
                        }
                        is CacheMetadata.Permanent -> false
                    }
                }

                if (affectedEntries.isNotEmpty()) {
                    logger.info("File changed: ${file.path}, invalidating ${affectedEntries.size} cache entries")
                    affectedEntries.forEach { (key, _) ->
                        cache.invalidate(key)
                        runBlocking {
                            stampedeCache.invalidate(key)
                        }
                        logger.debug("Invalidated: $key")
                    }
                }
            }
        }
    }

    private fun getFileTimestamp(path: String): Long {
        return try {
            val file = VirtualFileManager.getInstance().findFileByUrl("file://$path")
            file?.timeStamp ?: 0L
        } catch (e: Exception) {
            logger.warn("Failed to get timestamp for $path", e)
            0L
        }
    }

    private suspend fun getConfigFiles(workDir: String, configEnvironment: String?): List<VirtualFile> {
        if (workDir.isBlank()) return emptyList()

        val basePath = VirtualFileManager.getInstance().findFileByUrl("file://$workDir") ?: return emptyList()
        return configResolver.resolveConfigFiles(basePath, refresh = false, configEnvironment = configEnvironment)
    }

    // === Data Classes ===

    private data class CacheEntry(
        val value: Result<*>,
        val metadata: CacheMetadata
    )

    private sealed class CacheMetadata {
        data class ExecutableDependent(
            val executablePath: String,
            val executableTimestamp: Long
        ) : CacheMetadata()

        data class ConfigDependent(
            val configTimestamps: Map<VirtualFile, Long>
        ) : CacheMetadata()

        data object Permanent : CacheMetadata()
    }
}
