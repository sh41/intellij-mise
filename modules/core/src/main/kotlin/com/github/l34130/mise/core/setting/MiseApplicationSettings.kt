package com.github.l34130.mise.core.setting

import com.intellij.execution.Platform
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

    override fun initializeComponent() {
        // Don't auto-populate - let it stay empty
    }
    companion object {
        private val logger = logger<MiseApplicationSettings>()

        /**
         * Auto-detect mise executable path as fallback.
         * This is used at runtime when mise is not in PATH and no explicit setting exists.
         * NOTE: Auto-detected paths are NOT persisted to settings.
         *
         * WARNING: This method is kept for backwards compatibility but should NOT be called
         * from runtime code. Use explicit configuration or PATH default instead.
         */
        @Deprecated("Auto-detection removed from runtime. Users should configure explicitly or use PATH.")
        fun getMiseExecutablePath(): String? {
            logger.warn("getMiseExecutablePath() called - this method is deprecated and should not be used at runtime")

            // try to find the mise executable in the PATH
            val path = EnvironmentUtil.getValue("PATH")
            if (path != null) {
                for (dir in StringUtil.tokenize(path, File.pathSeparator)) {
                    val file = File(dir, "mise")
                    if (file.canExecute()) {
                        return file.toPath().absolutePathString()
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

                    // WSL discovery removed - should not auto-detect WSL for Windows projects
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
