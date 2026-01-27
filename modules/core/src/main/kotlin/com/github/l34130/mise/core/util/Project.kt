package com.github.l34130.mise.core.util

import com.github.l34130.mise.core.setting.MiseApplicationSettings
import com.github.l34130.mise.core.wsl.WslPathUtils
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import com.intellij.util.SystemProperties
import com.intellij.util.application

private val logger = Logger.getInstance("com.github.l34130.mise.core.util.Project")

/**
 * Gets the canonical path for the Mise project directory.
 *
 * This provides a consistent way to get the project path suitable for use with
 * mise commands and file system operations.
 *
 * @return The canonical path of the project directory
 * @throws IllegalStateException when the project directory cannot be resolved to a canonical path
 */
fun Project.guessMiseProjectPath(): String {
    val projectDir = guessMiseProjectDir()
    return checkNotNull(projectDir.canonicalPath) {
        "Mise project dir has no canonical path. project=${this.name}, dir=${projectDir.path}, fs=${projectDir.fileSystem.protocol}"
    }
}

/**
 * Gets the VirtualFile for the Mise project directory.
 *
 * This provides a consistent way to get the VirtualFile that represents the project home
 *
 * @return The virtual file representing the project directory, or falls back to the user's home directory
 * @throws IllegalStateException when neither the project directory nor user home can be resolved
 */
fun Project.guessMiseProjectDir(): VirtualFile {
    val projectDir = guessProjectDir()
    if (projectDir != null) return projectDir

    val userHome = SystemProperties.getUserHome()
    val userHomeDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(userHome)
    checkNotNull(userHomeDir) {
        "Project has no base directory and user home is unavailable. project=${this.name}, userHome=$userHome"
    }

    logger.warn("guessProjectDir() returned null for project ${this.name}, falling back to user home: ${userHomeDir.path}")
    return userHomeDir
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
    val distribution = getWslDistribution()
    if (distribution != null) {
        val shellPath = distribution.shellPath
        if (shellPath.isNotBlank()) {
            return distribution.getWindowsPath(shellPath)
        }
    }
    return EnvironmentUtil.getValue("SHELL")
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
    val distribution = getWslDistribution()
    if (distribution != null) {
        val userHome = distribution.userHome
        if (!userHome.isNullOrBlank()) {
            return distribution.getWindowsPath(userHome)
        }
    }
    return SystemProperties.getUserHome()
}

fun Project.getWslDistribution(): WSLDistribution? {
    if (!SystemInfo.isWindows) return null

    val projectPath = guessMiseProjectPath()
    val distributionId =
        WslPathUtils.extractDistribution(projectPath)
            ?: run {
                val configuredPath = application.service<MiseApplicationSettings>().state.executablePath
                configuredPath.takeIf { it.isNotBlank() }?.let { WslPathUtils.extractDistribution(it) }
            }

    if (distributionId.isNullOrBlank()) return null

    return WslDistributionManager.getInstance().installedDistributions
        .firstOrNull { it.msId.equals(distributionId, ignoreCase = true) }
}

fun Project.waitForProjectCache(): Boolean {
    // Previously waited on async project cache; resolution is now synchronous.
    return true
}
