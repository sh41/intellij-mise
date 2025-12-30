package com.github.l34130.mise.core.setting

import com.github.l34130.mise.core.wsl.WslCommandHelper
import com.intellij.execution.Platform
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.execution.wsl.getWslPathSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.EnvironmentUtil
import com.intellij.util.SystemProperties
import com.intellij.util.system.OS
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

@Service(Service.Level.APP)
@State(name = "com.github.l34130.mise.settings.MiseApplicationSettings", storages = [Storage("mise.xml")])
class MiseApplicationSettings : PersistentStateComponent<MiseApplicationSettings.MyState> {
    private var myState = MyState()

    override fun getState(): MyState = myState

    override fun loadState(state: MyState) {
        myState = state.clone()

        // Migration: Clear old WSL auto-discovered paths if they exist
        if (SystemInfo.isWindows && myState.executablePath.contains("wsl.exe")) {
            logger.info("Migrating old WSL executable setting. Clearing to use PATH default.")
            myState.executablePath = ""
        }
    }

    override fun noStateLoaded() {
        myState = MyState()  // Just use empty defaults
    }

    companion object {
        private val logger = logger<MiseApplicationSettings>()

        /**
         * Auto-detect mise executable path for use by MiseExecutableManager.
         * Checks PATH and common platform-specific installation locations.
         *
         * @param workDir Working directory to determine context (WSL vs Windows). Must not be blank.
         * @return Detected path to mise executable, or null if not found
         * @throws IllegalArgumentException if workDir is blank
         */
        internal fun getMiseExecutablePath(workDir: String): String? {
            require(workDir.isNotBlank()) { "workDir must not be blank" }

            // Check if we're in WSL context using the existing helper
            val isWslContext = WslCommandHelper.isWslUncPath(workDir)

            // For WSL projects, use 'which' command in WSL environment
            if (isWslContext) {
                return try {
                    // Extract distribution from the workDir UNC path
                    val distributionMsId = com.github.l34130.mise.core.wsl.WslPathUtils.extractDistribution(workDir)
                    if (distributionMsId == null) {
                        logger.warn("Cannot extract WSL distribution from workDir: $workDir")
                        return null
                    }

                    val distribution =
                        WslDistributionManager.getInstance().getOrCreateDistributionByMsId(distributionMsId)
                    val wslOptions =
                        WSLCommandLineOptions().setRemoteWorkingDirectory(distribution.getWslPathSafe(Path(workDir)))
                    val output = distribution.executeOnWsl(listOf("bash", "-c", "type -P mise"), wslOptions, 1000, null)

                    if (output.exitCode == 0) {
                        val unixPath = output.stdout.trim().lines().firstOrNull()?.takeIf { it.isNotBlank() }
                        if (unixPath != null) {
                            // Convert Unix path to Windows UNC path for IDE access
                            val uncPath = com.github.l34130.mise.core.wsl.WslPathUtils.convertWslToWindowsUncPath(
                                unixPath,
                                distributionMsId
                            )
                            logger.info("Detected mise in WSL: $unixPath -> $uncPath")
                            return uncPath
                        }
                    } else {
                        logger.debug("'which mise' command failed in WSL (exit code: ${output.exitCode})")
                    }
                    null
                } catch (e: Exception) {
                    logger.debug("Failed to detect mise in WSL", e)
                    null
                }
            }

            // try to find the mise executable in the PATH
            val path = EnvironmentUtil.getValue("PATH")
            if (path != null) {
                val executableNames = when (OS.CURRENT.platform) {
                    Platform.WINDOWS -> listOf("mise.exe", "mise.cmd", "mise.bat", "mise")
                    else -> listOf("mise")
                }

                for (dir in StringUtil.tokenize(path, File.pathSeparator)) {
                    for (execName in executableNames) {
                        val file = File(dir, execName)
                        if (file.exists() && file.canExecute()) {
                            return file.toPath().absolutePathString()
                        }
                    }
                }
            }

            // try to find the mise executable in usual system-specific directories
            when (OS.CURRENT.platform) {
                Platform.WINDOWS -> {
                    val localAppData: Path? = EnvironmentUtil.getValue("LOCALAPPDATA")?.let { Path.of(it) }
                    if (localAppData != null) {
                        val path = localAppData.resolve("Microsoft/WinGet/Links/mise.exe")
                        if (runCatching { path.toFile().canExecute() }.getOrNull() == true) {
                            return path.absolutePathString()
                        }
                    }

                    val userHome = Path.of(SystemProperties.getUserHome())
                    val path = userHome.resolve("AppData/Local/Microsoft/WinGet/Links/mise.exe")
                    if (runCatching { path.toFile().canExecute() }.getOrNull() == true) {
                        return path.absolutePathString()
                    }
                }

                Platform.UNIX -> {
                    // do nothing
                }
            }

            return null
        }
    }

    class MyState : Cloneable {
        var executablePath: String = ""

        public override fun clone(): MyState =
            MyState().also {
                it.executablePath = executablePath
            }
    }
}
