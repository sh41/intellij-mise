package com.github.l34130.mise.core.setting

import com.github.l34130.mise.core.util.StampedeProtectedCache
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.application
import kotlinx.coroutines.runBlocking

/**
 * Single source of truth for mise executable path resolution.
 *
 * Resolution priority:
 * 1. Project-level user-configured path (if set)
 * 2. Application-level user-configured path (if set)
 * 3. Auto-detected path from PATH or common locations (cached)
 *
 * The auto-detected path is cached and invalidated when the executable file changes.
 */
@Service(Service.Level.PROJECT)
class MiseExecutableManager(private val project: Project) {
    private val logger = logger<MiseExecutableManager>()

    // Cache for auto-detected path with stampede protection
    private data class CachedPath(val path: String, val timestamp: Long)
    private val pathCache = StampedeProtectedCache<String, CachedPath>()

    init {
        // Listen to file system changes to invalidate cache
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach { event ->
                        val path = event.path
                        // Invalidate if cached executable changed
                        runBlocking {
                            val cached = pathCache.getIfPresent(CACHE_KEY)
                            if (cached != null && path == cached.path) {
                                logger.debug("Mise executable file changed, invalidating cache: $path")
                                pathCache.invalidate(CACHE_KEY)
                            }
                        }
                    }
                }
            }
        )
    }

    companion object {
        private const val CACHE_KEY = "auto-detected-path"
    }

    /**
     * Get the mise executable path to use.
     * This is the single source of truth for all mise operations.
     *
     * @param workDir Working directory context (used for WSL path detection). If null, uses project base path.
     * @return Full path to mise executable
     * @throws IllegalStateException if workDir is null/blank and project has no base path
     */
    fun getExecutablePath(workDir: String? = null): String {
        val effectiveWorkDir = workDir?.takeIf { it.isNotBlank() } ?: project.guessMiseProjectPath()

        val projectSettings = project.service<MiseProjectSettings>()
        val appSettings = application.service<MiseApplicationSettings>()

        // Priority 1: Project-level user configuration
        val projectPath = projectSettings.state.executablePath
        if (projectPath.isNotBlank()) {
            logger.debug("Using project-configured executable: $projectPath")
            return projectPath
        }

        // Priority 2: Application-level user configuration
        val appPath = appSettings.state.executablePath
        if (appPath.isNotBlank()) {
            logger.debug("Using app-configured executable: $appPath")
            return appPath
        }

        // Priority 3: Auto-detect (with caching)
        return getAutoDetectedPath(effectiveWorkDir)
    }

    /**
     * Get the auto-detected executable path (for UI placeholder display).
     * This does NOT check user-configured settings.
     *
     * @param workDir Working directory context. If null, uses project base path.
     * @return Auto-detected path or "mise" as fallback
     * @throws IllegalStateException if workDir is null/blank and project has no base path
     */
    fun getAutoDetectedPath(workDir: String? = null): String = runBlocking {
        val effectiveWorkDir = workDir?.takeIf { it.isNotBlank() } ?: project.guessMiseProjectPath()

        // Use stampede-protected cache
        val cached = pathCache.get(
            key = CACHE_KEY,
            isValid = { cachedPath ->
                // Validate: check if file timestamp hasn't changed
                val currentTimestamp = getFileTimestamp(cachedPath.path)
                currentTimestamp == cachedPath.timestamp && currentTimestamp > 0
            }
        ) {
            // Compute: detect mise executable (pass effectiveWorkDir for WSL context)
            val detected = MiseApplicationSettings.getMiseExecutablePath(effectiveWorkDir)

            if (detected != null) {
                val timestamp = getFileTimestamp(detected)
                logger.info("Auto-detected mise executable: $detected")
                CachedPath(detected, timestamp)
            } else {
                // Fallback to "mise" (will rely on PATH at runtime)
                logger.info("Could not auto-detect mise executable, using 'mise' as fallback")
                CachedPath("mise", 0L)
            }
        }

        cached.path
    }

    private fun getFileTimestamp(path: String): Long {
        return try {
            VirtualFileManager.getInstance().findFileByUrl("file://$path")?.timeStamp ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
