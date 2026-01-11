package com.github.l34130.mise.core.setting

import com.github.l34130.mise.core.cache.MiseCacheService
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.application
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.messages.Topic

/**
 * Single source of truth for mise executable path resolution.
 *
 * Resolution priority:
 * 1. Project-level user-configured path (if set)
 * 2. Application-level user-configured path (if set)
 * 3. Auto-detected path from PATH or common locations
 *
 * All resolved paths are cached per project and invalidated when:
 * - User changes the executable path in settings
 * - The cached path is modified/deleted (detected via VFS listener)
 */
@Service(Service.Level.PROJECT)
class MiseExecutableManager(private val project: Project) {
    private val logger = logger<MiseExecutableManager>()
    private val cacheService = project.service<MiseCacheService>()

    init {
        val connection = project.messageBus.connect()

        // Listen to settings changes
        connection.subscribe(
            MiseSettingsListener.TOPIC,
            object : MiseSettingsListener {
                override fun settingsChanged() {
                    handleExecutableChange("settings changed")
                }
            }
        )

        // Listen to file system changes
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach { event ->
                        val path = event.path
                        // Check if the changed file matches either cached path
                        val execCached = cacheService.getCachedExecutable(EXECUTABLE_KEY)
                        val autoCached = cacheService.getCachedExecutable(AUTO_DETECTED_KEY)
                        if ((path == execCached) || (path == autoCached)) {
                            handleExecutableChange("file changed: $path")
                        }
                    }
                }
            }
        )

        // Warm cache on startup to avoid EDT blocking on first use
        // This runs in background and failure is non-fatal
        application.executeOnPooledThread {
            try {
                logger.debug("Warming executable path cache on project startup")
                getExecutablePath()
                logger.debug("Executable path cache warmed successfully")
            } catch (e: Exception) {
                logger.warn("Failed to warm executable cache on startup - will compute on first use", e)
            }
        }
    }

    /**
     * Handle executable change from any source (settings or VFS).
     * Invalidates cache, broadcasts to listeners, and re-warms cache in background.
     */
    private fun handleExecutableChange(reason: String) {
        logger.info("Mise executable changed ($reason), invalidating cache and notifying listeners")
        cacheService.invalidateAllExecutables()
        project.messageBus.syncPublisher(MISE_EXECUTABLE_CHANGED).run()

        // Re-warm cache in background to avoid EDT blocking on next access
        application.executeOnPooledThread {
            try {
                logger.debug("Re-warming executable path cache after invalidation")
                getExecutablePath()
                logger.debug("Executable path cache re-warmed successfully")
            } catch (e: Exception) {
                logger.warn("Failed to re-warm executable cache after invalidation", e)
            }
        }
    }

    companion object {
        const val EXECUTABLE_KEY = "executable-path"
        const val AUTO_DETECTED_KEY = "auto-detected-path"

        /**
         * Topic broadcast when the mise executable path changes.
         * This includes changes from:
         * - User modifying executable path in settings
         * - Executable file being modified/deleted via VFS
         */
        @JvmField
        @Topic.ProjectLevel
        val MISE_EXECUTABLE_CHANGED = Topic(
            "Mise Executable Changed",
            Runnable::class.java,
            Topic.BroadcastDirection.NONE
        )
    }

    fun matchesMiseExecutablePath(commandLine: GeneralCommandLine): Boolean {
        val candidateParts = commandLine.getCommandLineList(null)
        val execParts = getExecutableParts()
        return execParts.isNotEmpty() && candidateParts.size >= execParts.size && execParts == candidateParts.take(
            execParts.size
        )
    }

    fun getExecutableParts(): List<String> = ParametersListUtil.parse(getExecutablePath())

    /**
     * Get the mise executable path to use.
     * This is the single source of truth for all mise operations.
     * All WSL handling is delegated to the Eel layer.
     * This is NEVER a multi-argument call to something like `wsl.exe -d <DISTRO> -e cmd`
     *
     * @return Full path to mise executable
     */
    fun getExecutablePath(): String {
        return cacheService.getOrComputeExecutable(EXECUTABLE_KEY) {
            val projectSettings = project.service<MiseProjectSettings>()
            val appSettings = application.service<MiseApplicationSettings>()

            // Priority 1: Project-level user configuration
            val projectPath = projectSettings.state.executablePath
            if (projectPath.isNotBlank()) {
                logger.debug("Using project-configured executable: $projectPath")
                return@getOrComputeExecutable projectPath
            }

            // Priority 2: Application-level user configuration
            val appPath = appSettings.state.executablePath
            if (appPath.isNotBlank()) {
                logger.debug("Using app-configured executable: $appPath")
                return@getOrComputeExecutable appPath
            }

            // Priority 3: Auto-detect (with caching)
            getAutoDetectedPath()
        }
    }

    /**
     * Get the auto-detected executable path (for UI placeholder display).
     * This does NOT check user-configured settings.
     *
     * @return Auto-detected path or "mise" as fallback
     */
    fun getAutoDetectedPath(): String {
        return cacheService.getOrComputeExecutable(AUTO_DETECTED_KEY) {
            val projectPath = project.guessMiseProjectPath()

            // Detect mise executable (pass the project path for WSL context)
            val detected = MiseApplicationSettings.getMiseExecutablePath(projectPath)

            if (detected != null) {
                logger.info("Auto-detected mise executable: $detected")
                detected
            } else {
                // Fallback to "mise" (will rely on PATH at runtime)
                logger.info("Could not auto-detect mise executable, using 'mise' as fallback")
                "mise"
            }
        }
    }
}
