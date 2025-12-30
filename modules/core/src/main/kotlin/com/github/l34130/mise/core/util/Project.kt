package com.github.l34130.mise.core.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
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
