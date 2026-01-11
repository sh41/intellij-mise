package com.github.l34130.mise.core.util

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.EnvironmentUtil
import com.intellij.util.SystemProperties

/**
 * Gets the canonical path for the Mise project directory.
 *
 * This provides a consistent way to get the project path suitable for use with
 * mise commands and file system operations.
 *
 * @return The canonical path of the project directory, or falls back to the user's home directory if unavailable
 */
fun Project.guessMiseProjectPath(): String {
    return guessProjectDir()?.canonicalPath ?: SystemProperties.getUserHome()
}

/**
 * Gets the shell path appropriate for the project's execution environment.
 *
 * For WSL projects, returns the Windows UNC path to the user's shell
 * (e.g., `\\wsl.localhost\Ubuntu\usr\bin\zsh`).
 * For native projects, returns the shell from the SHELL environment variable.
 *
 * @return Shell path for the project environment, or null if unavailable
 */
fun Project.getProjectShell(): String? {
    val projectPath = guessMiseProjectPath()
    val distribution = WslPath.getDistributionByWindowsUncPath(projectPath)

    // WSL: return Windows UNC path to shell, Native: return $SHELL environment variable
    return distribution?.getWindowsPath(distribution.shellPath) 
        ?: EnvironmentUtil.getValue("SHELL")
}

/**
 * Gets the user home directory path appropriate for the project's execution environment.
 *
 * For WSL projects, returns the Windows UNC path to the WSL user's home directory
 * (e.g., `\\wsl.localhost\Ubuntu\home\steve`).
 * For native projects, returns the system user home directory.
 *
 * @return User home path for the project environment
 */
fun Project.getUserHomeForProject(): String {
    val projectPath = guessMiseProjectPath()
    val distribution = WslPath.getDistributionByWindowsUncPath(projectPath)

    return if (distribution != null) {
        // WSL: return Windows UNC path to home
        val userHome = distribution.userHome
        if (userHome != null) {
            distribution.getWindowsPath(userHome)
        } else {
            // Fallback to system home if WSL home not available
            SystemProperties.getUserHome()
        }
    } else {
        // Native: return system home
        SystemProperties.getUserHome()
    }
}

fun Project.getWslDistribution(): WSLDistribution? {
    return WslPath.getDistributionByWindowsUncPath(guessMiseProjectPath())
}
