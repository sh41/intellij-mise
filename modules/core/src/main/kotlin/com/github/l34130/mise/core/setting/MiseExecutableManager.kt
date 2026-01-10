package com.github.l34130.mise.core.setting

import com.github.l34130.mise.core.util.StampedeProtectedCache
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
import kotlinx.coroutines.runBlocking

/**
 * Single source of truth for mise executable path resolution.
 *
 * Resolution priority:
 * 1. Project-level user-configured path (if set)
 * 2. Application-level user-configured path (if set)
 * 3. Auto-detected path from PATH or common locations
 *
 * All resolved paths are cached per project and invalidated when:
 * - User changes executable path in settings
 * - Executable file is modified/deleted (detected via VFS listener)
 */
@Service(Service.Level.PROJECT)
class MiseExecutableManager(private val project: Project) {
    private val logger = logger<MiseExecutableManager>()

    // Cache for resolved executable path with stampede protection
    // Single cache entry per project (one project = one mise executable)
    private val executablePathCache = StampedeProtectedCache<Unit, String>()
    private val autoDetectedPathCache = StampedeProtectedCache<Unit, String>()

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
                        runBlocking {
                            // Check if the changed file matches either cached path
                            val execCached = executablePathCache.getIfPresent(Unit)
                            val autoCached = autoDetectedPathCache.getIfPresent(Unit)
                            if ((execCached != null && path == execCached) ||
                                (autoCached != null && path == autoCached)) {
                                handleExecutableChange("file changed: $path")
                            }
                        }
                    }
                }
            }
        )
    }

    /**
     * Handle executable change from any source (settings or VFS).
     * Invalidates cache and broadcasts to listeners.
     */
    private fun handleExecutableChange(reason: String) {
        logger.info("Mise executable changed ($reason), invalidating cache and notifying listeners")
        runBlocking {
            executablePathCache.invalidateAll()
            autoDetectedPathCache.invalidateAll()
            project.messageBus.syncPublisher(MISE_EXECUTABLE_CHANGED).run()
        }
    }

    companion object {

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
    fun getExecutablePath(): String = runBlocking {
        executablePathCache.get(
            key = Unit,
            isValid = { true } // Listeners handle invalidation
        ) {
            val projectSettings = project.service<MiseProjectSettings>()
            val appSettings = application.service<MiseApplicationSettings>()

            // Priority 1: Project-level user configuration
            val projectPath = projectSettings.state.executablePath
            if (projectPath.isNotBlank()) {
                logger.debug("Using project-configured executable: $projectPath")
                return@get projectPath
            }

            // Priority 2: Application-level user configuration
            val appPath = appSettings.state.executablePath
            if (appPath.isNotBlank()) {
                logger.debug("Using app-configured executable: $appPath")
                return@get appPath
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
    fun getAutoDetectedPath(): String = runBlocking {
        autoDetectedPathCache.get(
            key = Unit,
        ) {
            val projectPath = project.guessMiseProjectPath()

            // Detect mise executable (pass project path for WSL context)
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
