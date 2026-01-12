package com.github.l34130.mise.core.util

import com.github.l34130.mise.core.MiseProjectService
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.SystemProperties
import java.util.concurrent.TimeUnit

/**
 * Gets the canonical path for the Mise project directory.
 *
 * This provides a consistent way to get the project path suitable for use with
 * mise commands and file system operations.
 *
 * @return The canonical path of the project directory, or falls back to the user's home directory if unavailable
 */
fun Project.guessMiseProjectPath(): String {
    val projectDir = guessProjectDir()
    val path = projectDir?.canonicalPath ?: SystemProperties.getUserHome()
    if (projectDir == null) {
        com.intellij.openapi.diagnostic.Logger.getInstance("com.github.l34130.mise.core.util")
            .warn("guessProjectDir() returned null for project ${this.name}, falling back to user home: $path")
    }
    return path
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
    return this.service<MiseProjectService>().getShellPath()
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
    return this.service<MiseProjectService>().getUserHome()
}

fun Project.getWslDistribution(): WSLDistribution? {
    return this.service<MiseProjectService>().getWslDistribution()
}

fun Project.waitForProjectCache(): Boolean {
    return this.service<MiseProjectService>().isCacheReady.await(10, TimeUnit.SECONDS)
}
