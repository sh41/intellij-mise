package com.github.l34130.mise.core.wsl

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath.Companion.getDistributionByWindowsUncPath
import com.intellij.openapi.diagnostic.logger

/**
 * Helper for WSL path transformations.
 *
 * Transforms Windows/WSL UNC paths in command parameters and environment variables
 * to POSIX paths for WSL execution.
 *
 * Uses cached WSL distribution from MiseProjectService (initialized at startup).
 */
object WslCommandHelper {
    private val logger = logger<WslCommandHelper>()

    /**
     * Converts WSL UNC paths and Windows drive paths embedded in a string to POSIX paths.
     *
     * Examples of conversions:
     * - \\wsl$\distro\path or //wsl$/distro/path -> /path
     * - \\wsl.localhost\distro\path or //wsl.localhost/distro/path -> /path
     * - C:/path or C:\path -> /mnt/c/path
     *
     * Paths are matched greedily until invalid Windows path characters (< > : " | ? *) are encountered.
     * Uses convertSingleWslPath() for actual conversion to respect implementation-specific settings.
     */
    fun convertWslPathsInString(input: String, distribution: WSLDistribution): String {
        var result = input

        // Convert Windows drive paths (C:/, D:\) to WSL mount paths (/mnt/c/, /mnt/d/)
        // Matches drive letter followed by colon and path, stopping at whitespace or invalid chars
        val drivePattern = Regex("""([A-Za-z]):([\\/][^\s<>:"|?*]*)""")
        val driveMatches = drivePattern.findAll(result).toList()

        // Process in reverse order to maintain string indices
        for (match in driveMatches.reversed()) {
            val windowsPath = match.value
            try {
                val wslPath = convertSingleWslPath(windowsPath, distribution)
                if (wslPath != null) {
                    result = result.substring(0, match.range.first) + wslPath + result.substring(match.range.last + 1)
                } else {
                    logger.debug("convertSingleWslPath returned null for drive path: $windowsPath")
                }
            } catch (e: Exception) {
                logger.debug("Failed to convert Windows drive path: $windowsPath", e)
            }
        }

        // Convert WSL UNC paths to POSIX paths
        // Matches \\wsl$ or \\wsl.localhost (with forward or backslashes)
        // followed by distro name and path, stopping at invalid Windows path characters
        val pattern = Regex("""([\\/]{2}wsl(?:\$|\.localhost)[\\/][^\\/]+[\\/][^<>:"|?*]+)""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(result).toList()

        // Process in reverse order to maintain string indices
        for (match in matches.reversed()) {
            val uncPath = match.groupValues[1]

            // Only convert paths from the same distribution
            val pathDistribution = getDistributionByWindowsUncPath(uncPath)
            if (pathDistribution?.msId != distribution.msId) {
                continue
            }

            try {
                val posixPath = convertSingleWslPath(uncPath, distribution)
                if (posixPath != null) {
                    result = result.substring(0, match.range.first) + posixPath + result.substring(match.range.last + 1)
                } else {
                    logger.warn("convertSingleWslPath returned null for WSL UNC path: $uncPath")
                }
            } catch (e: Exception) {
                logger.warn("Failed to convert WSL UNC path: $uncPath", e)
            }
        }

        return result
    }

    fun convertSingleWslPath(windowsPath: String, distribution: WSLDistribution): String? {
        return try {
            // Normalize to backslashes for Path.of() on Windows
            val normalizedPath = windowsPath.replace('/', '\\')
            distribution.getWslPath(java.nio.file.Path.of(normalizedPath))
        } catch (e: Exception) {
            logger.debug("Failed to convert path using getWslPath: $windowsPath", e)
            null
        }
    }

}
