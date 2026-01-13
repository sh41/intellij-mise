package com.github.l34130.mise.core.command

import com.github.l34130.mise.core.cache.MiseCacheService
import com.github.l34130.mise.core.setting.MiseApplicationSettings
import com.github.l34130.mise.core.setting.MiseProjectSettings
import com.github.l34130.mise.core.setting.MiseSettingsListener
import com.github.l34130.mise.core.util.getProjectShell
import com.github.l34130.mise.core.util.getWslDistribution
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.github.l34130.mise.core.wsl.WslCommandHelper
import com.github.l34130.mise.core.wsl.resolveUserHomeAbbreviations
import com.intellij.execution.Platform
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.SystemProperties
import com.intellij.util.application
import com.intellij.util.concurrency.ThreadingAssertions.assertBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.messages.Topic
import com.intellij.util.system.OS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

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
class MiseExecutableManager(
    private val project: Project,
    private val cs: CoroutineScope
) {
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
    }


    /**
     * Handle executable change from any source (settings or VFS).
     * Invalidates cache, broadcasts to listeners, and re-warms cache in the background.
     */
    fun handleExecutableChange(reason: String) {
        logger.info("Mise executable changed ($reason), invalidating cache and notifying listeners")
        cacheService.invalidateAllExecutables()
        warmCache()
        project.messageBus.syncPublisher(MISE_EXECUTABLE_CHANGED).run()
    }


    fun warmCache() {
        cs.launch(Dispatchers.IO) {
            try {
                logger.debug("Warming executable path cache")
                getExecutablePath()
                logger.debug("Executable path cache re-warmed successfully")
            } catch (e: Exception) {
                logger.warn("Failed to warm executable cache", e)
            }
        }
    }

    companion object {
        const val EXECUTABLE_KEY = "executable-path"
        const val AUTO_DETECTED_KEY = "auto-detected-path"

        /**
         * Version command used for mise detection and verification.
         * Using -vv ensures the debug output shows the actual executable path.
         */
        private const val MISE_VERSION_COMMAND = "version -vv"

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
            val detected = detectMiseExecutablePath(projectPath)

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

    /**
     * Auto-detect the mise executable path using shell commands and common installation locations.
     * Checks PATH using platform-appropriate commands and falls back to common directories.
     *
     * @param workDir Working directory to determine context (WSL vs. native). Must not be blank.
     * @return Detected path to mise executable, or null if not found
     * @throws IllegalArgumentException if workDir is blank
     */
    private fun detectMiseExecutablePath(workDir: String): String? {
        require(workDir.isNotBlank()) { "workDir must not be blank" }

        // Get the shell path using project extension (handles WSL vs. native)
        val shell = project.getProjectShell()
        val distribution = project.getWslDistribution()

        // Route to appropriate detection
        return when {
            shell != null && (distribution != null || OS.CURRENT.platform == Platform.UNIX) -> {
                // WSL or Unix
                logger.trace("Detecting mise executable on Linux (workDir: $workDir)")
                detectOnUnix(workDir, shell, distribution)
            }

            OS.CURRENT.platform == Platform.WINDOWS -> {
                logger.debug("Detecting mise executable on Windows (workDir: $workDir)", Throwable())
                // Native Windows (not WSL)
                detectOnWindows()
            }

            else -> {
                logger.warn("Could not determine shell for mise detection (workDir: $workDir)")
                null
            }
        }
    }

    /**
     * Detect mise executable by running a command and parsing the debug output from 'version -vv'.
     * Captures both stdout and stderr as debug output can appear in either stream.
     *
     * @param executable The executable to run (e.g., "mise" or full path like "/usr/bin/mise")
     * @param shellCommand The shell executable (e.g., "/bin/zsh", "cmd")
     * @param shellArgs Arguments to pass to the shell (e.g., ["-l", "-c"] or ["/c"])
     * @param workDir Working directory for the command
     * @param distribution Optional WSL distribution for path conversion
     * @return Detected mise executable path, or null if not found
     */
    @RequiresBackgroundThread
    private fun detectMiseFromVersionCommand(
        executable: String,
        shellCommand: String,
        shellArgs: List<String>,
        workDir: String,
        distribution: WSLDistribution? = null
    ): String? {
        assertBackgroundThread()

        try {
            val posixExecutable = distribution?.let { dist ->
                WslCommandHelper.convertWslPathsInString(executable, dist)
            } ?: executable

            // Build the full command: shell shellArgs "<executable> version -vv"
            val fullCommand = "$posixExecutable $MISE_VERSION_COMMAND"
            val commandLine = GeneralCommandLine(listOf(shellCommand) + shellArgs + listOf(fullCommand))
                .withWorkingDirectory(Path.of(workDir))

            // Add an injection marker to prevent environment customization during detection
            MiseCommandLineHelper.environmentSkipCustomization(commandLine.environment)

            // Execute command using a shared helper (failures are expected during detection)
            val processOutput = MiseCommandLine.executeCommandLine(commandLine, allowedToFail = true, timeout = 3000)
                .getOrElse { return null }

            // Capture both stdout and stderr
            val combinedOutput = processOutput.stderr + "\n" + processOutput.stdout
            logger.debug(combinedOutput)
            // Find the line with "ARGS: " that ends with " version -vv"
            val argsLine = combinedOutput.lines().firstOrNull { line ->
                line.contains("MISE_BIN: ")
            }

            if (argsLine != null) {
                // Extract path: everything after "MISE_BIN: "
                val misePath = argsLine.substringAfter("MISE_BIN: ")

                if (misePath.isNotBlank() && misePath != executable) {
                    // We found the actual path! For WSL, convert to Windows UNC
                    val result = resolveUserHomeAbbreviations(misePath, project).toString()
                    logger.info("Detected mise executable: $result")
                    return result
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to detect mise via version command with executable='$executable'", e)
        }

        return null
    }

    /**
     * Detect mise on Windows using the 'where' command and common installation paths.
     */
    @RequiresBackgroundThread
    private fun detectOnWindows(): String? {
        assertBackgroundThread()

        val workDir = project.guessMiseProjectPath()

        // Primary: Try 'mise version -vv' via cmd
        val detected = detectMiseFromVersionCommand(
            executable = "mise",
            shellCommand = "cmd",
            shellArgs = listOf("/c"),
            workDir = workDir,
            distribution = null
        )

        if (detected != null) {
            // Verify by running the full path directly
            val verified = detectMiseFromVersionCommand(
                executable = detected,
                shellCommand = "cmd",
                shellArgs = listOf("/c"),
                workDir = workDir,
                distribution = null
            )

            if (verified != null) {
                return verified
            } else {
                logger.warn("Detected mise at $detected but verification failed")
            }
        }

        // fallback: Check common installation paths
        val userHome = Path(SystemProperties.getUserHome())

        val candidatePaths = listOf(
            userHome.resolve("AppData/Local/Microsoft/WinGet/Links/mise.exe"),
            userHome.resolve("scoop/apps/mise/current/bin/mise.exe")
        )

        for (candidatePath in candidatePaths) {
            if (runCatching { candidatePath.toFile().canExecute() }.getOrNull() == true) {
                val pathStr = candidatePath.absolutePathString()

                // Verify the fallback path
                val verified = detectMiseFromVersionCommand(
                    executable = pathStr,
                    shellCommand = "cmd",
                    shellArgs = listOf("/c"),
                    workDir = workDir,
                    distribution = null
                )

                if (verified != null) {
                    logger.info("Detected mise at fallback path: $verified")
                    return verified
                }
            }
        }

        return null
    }

    /**
     * Detect mise on Unix-like systems (Linux/macOS/WSL) using the user's shell.
     * Uses login shell (-l) to ensure rc files are sourced (e.g., ~/.bashrc, ~/.zshrc).
     * When workDir is a WSL UNC path, the Eel layer automatically handles WSL execution.
     *
     * @param workDir Working directory (can be WSL UNC path or native path)
     * @param shell Shell path to use (Windows UNC for WSL, native path for Unix/macOS)
     * @param distribution WSL distribution if workDir is WSL path, null otherwise
     * @return Detected path to mise executable, or null if not found
     */
    @RequiresBackgroundThread
    private fun detectOnUnix(
        workDir: String,
        shell: String,
        distribution: WSLDistribution?
    ): String? {
        assertBackgroundThread()

        // Primary: Try 'mise version -vv' with login shell
        // This works even if mise is a shell function wrapper
        val detected = detectMiseFromVersionCommand(
            executable = "mise",
            shellCommand = shell,
            shellArgs = listOf("-l", "-c"),
            workDir = workDir,
            distribution = distribution
        )

        if (detected != null) {
            // Verify by running the full path directly (not through shell)
            val verified = detectMiseFromVersionCommand(
                executable = detected,
                shellCommand = shell,
                shellArgs = listOf("-c"),  // No -l needed, using the full path
                workDir = workDir,
                distribution = distribution
            )

            if (verified != null) {
                return verified
            } else {
                logger.warn("Detected mise at $detected but verification failed")
            }
        }

        // Fallback: Check ~/.local/bin/mise
        val defaultMisePath = "~/.local/bin/mise"
        val localBinPath = resolveUserHomeAbbreviations(defaultMisePath, project)

        if (runCatching { localBinPath.toFile().canExecute() }.getOrNull() == true) {
            val pathStr = localBinPath.absolutePathString()

            // Verify the fallback path
            val verified = detectMiseFromVersionCommand(
                executable = pathStr,
                shellCommand = shell,
                shellArgs = listOf("-c"),
                workDir = workDir,
                distribution = distribution
            )

            if (verified != null) {
                logger.info("Detected mise in $defaultMisePath: $verified")
                return verified
            }
        }

        return null
    }

}
