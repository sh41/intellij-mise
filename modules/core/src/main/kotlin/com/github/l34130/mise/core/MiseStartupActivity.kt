package com.github.l34130.mise.core

import com.github.l34130.mise.core.util.getProjectShell
import com.github.l34130.mise.core.util.getUserHomeForProject
import com.github.l34130.mise.core.util.getWslDistribution
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class MiseStartupActivity :
    ProjectActivity,
    DumbAware {
    override suspend fun execute(project: Project) {
        // Pre-warm WSL-related resolution for early callers.
        project.getWslDistribution()
        project.getUserHomeForProject()
        project.getProjectShell()
    }
}
