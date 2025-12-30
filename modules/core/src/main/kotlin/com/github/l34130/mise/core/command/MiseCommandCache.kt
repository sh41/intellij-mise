package com.github.l34130.mise.core.command

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.l34130.mise.core.MiseConfigFileResolver
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
class MiseCommandCache(project: Project) {
    private val logger = logger<MiseCommandCache>()
    private val configResolver = project.service<MiseConfigFileResolver>()

    // Caffeine cache with size-based eviction
    private val cache = Caffeine.newBuilder()
        .maximumSize(500)
        .removalListener<String, CacheEntry> { key: String?, _: CacheEntry?, cause ->
            if (cause == RemovalCause.SIZE) {
                logger.debug("Cache entry evicted due to size limit: $key")
            }
        }
        .build<String, CacheEntry>()

    // Per-key mutexes to prevent stampede
    private val computationLocks = mutableMapOf<String, Mutex>()
    private val locksMapMutex = Mutex()

    // Cache resolved PATH for "mise" executable
    private var cachedMisePathLocation: String? = null
    private var cachedMisePathTimestamp: Long = 0L

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
        workDir: String? = null,
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
        workDir: String? = null,
        configEnvironment: String? = null,
        compute: suspend () -> Result<T>
    ): Result<T> {
        // Check cache and validate
        val cached = cache.getIfPresent(key)
        if (cached != null && isCacheValid(cached, workDir, configEnvironment)) {
            logger.debug("Cache hit: $key")
            @Suppress("UNCHECKED_CAST")
            return cached.value as Result<T>
        }

        // Get or create a mutex for this key
        val mutex = locksMapMutex.withLock {
            computationLocks.getOrPut(key) { Mutex() }
        }

        // Lock for this specific key to prevent stampede
        return mutex.withLock {
            // Double-check cache after acquiring lock (another thread might have computed it)
            val recheck = cache.getIfPresent(key)
            if (recheck != null && isCacheValid(recheck, workDir, configEnvironment)) {
                logger.debug("Cache hit after lock: $key")
                @Suppress("UNCHECKED_CAST")
                return@withLock recheck.value as Result<T>
            }

            // Cache miss or invalid - compute value
            logger.debug("Cache miss or invalid: $key")
            val result = compute()

            // Store in cache with metadata
            result.onSuccess {
                val metadata = when (invalidation) {
                    CacheInvalidation.ON_EXECUTABLE_CHANGE -> {
                        CacheMetadata.ExecutableDependent(
                            executablePath = "mise",
                            executableTimestamp = resolveMiseInPath(workDir)?.let { getFileTimestamp(it) } ?: 0L
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

                cache.put(key, CacheEntry(result, metadata))
                logger.debug("Cached: $key (invalidation=$invalidation)")
            }

            // Clean up mutex after computation
            locksMapMutex.withLock {
                computationLocks.remove(key)
            }

            result
        }
    }

    // === Private Helper Methods ===

    private suspend fun isCacheValid(
        entry: CacheEntry,
        workDir: String?,
        configEnvironment: String?
    ): Boolean {
        return when (val metadata = entry.metadata) {
            is CacheMetadata.ExecutableDependent -> {
                // Check if executable changed
                val currentPath = resolveMiseInPath(workDir)
                val currentTimestamp = currentPath?.let { getFileTimestamp(it) } ?: 0L
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
                            cachedMisePathLocation?.contains(file.path) == true
                        }
                        is CacheMetadata.Permanent -> false
                    }
                }

                if (affectedEntries.isNotEmpty()) {
                    logger.info("File changed: ${file.path}, invalidating ${affectedEntries.size} cache entries")
                    affectedEntries.forEach { (key, entry) ->
                        cache.invalidate(key)
                        logger.debug("Invalidated: $key")

                        // Clear executable cache if needed
                        if (entry.metadata is CacheMetadata.ExecutableDependent) {
                            cachedMisePathLocation = null
                        }
                    }
                }
            }
        }
    }

    private fun resolveMiseInPath(workDir: String?): String? {
        // Check cached location first
        if (cachedMisePathLocation != null) {
            val cachedTimestamp = getFileTimestamp(cachedMisePathLocation!!)
            if (cachedTimestamp == cachedMisePathTimestamp && cachedTimestamp > 0) {
                return cachedMisePathLocation
            }
        }

        try {
            val command = if (workDir?.startsWith("\\\\wsl") == true) {
                GeneralCommandLine("which", "mise").withWorkDirectory(workDir)
            } else {
                if (System.getProperty("os.name").contains("Windows")) {
                    GeneralCommandLine("where", "mise")
                } else {
                    GeneralCommandLine("which", "mise")
                }
            }

            val output = ExecUtil.execAndGetOutput(command, 1000)
            if (output.exitCode == 0) {
                val path = output.stdout.trim().lines().firstOrNull()
                if (!path.isNullOrBlank()) {
                    cachedMisePathLocation = path
                    cachedMisePathTimestamp = getFileTimestamp(path)
                    logger.debug("Resolved mise in PATH: $path")
                    return path
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to resolve mise in PATH", e)
        }

        return null
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

    private suspend fun getConfigFiles(workDir: String?, configEnvironment: String?): List<VirtualFile> {
        if (workDir == null) return emptyList()

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
