package com.github.l34130.mise.core.wsl

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo

/**
 * Helper for WSL path transformations.
 *
 * Note: Process execution and distribution detection are handled by IntelliJ's
 * GeneralCommandLine and Eel APIs. This helper only transforms path parameters.
 */
object WslCommandHelper {
    private val logger = logger<WslCommandHelper>()

    /**
     * Check if a path is a WSL UNC path.
     * Uses IntelliJ's WslPath API.
     */
    fun isWslUncPath(path: String?): Boolean {
        if (!SystemInfo.isWindows || path.isNullOrBlank()) return false
        return WslPath.isWslUncPath(path)
    }

    /**
     * Convert a path parameter for use in mise commands.
     *
     * When running mise in WSL, parameters that are Windows UNC paths must be
     * converted to POSIX paths that mise can understand.
     *
     * @param path The path to convert (may be UNC, POSIX, or regular Windows path)
     * @return POSIX path if input was WSL UNC path, otherwise returns original
     *
     * Examples:
     * - "\\wsl.localhost\Ubuntu\home\user\.mise" → "/home/user/.mise"
     * - "/home/user/.mise" → "/home/user/.mise" (unchanged)
     * - "C:\Users\..." → "C:\Users\..." (unchanged, not WSL)
     */
    fun convertPathParameterForWsl(path: String): String {
        if (!SystemInfo.isWindows) return path

        // Try to parse as WSL UNC path
        val wslPath = WslPath.parseWindowsUncPath(path)
        if (wslPath != null) {
            logger.debug("Converted UNC path parameter: $path -> ${wslPath.linuxPath}")
            return wslPath.linuxPath
        }

        // Not a WSL path, return as-is
        return path
    }
}
