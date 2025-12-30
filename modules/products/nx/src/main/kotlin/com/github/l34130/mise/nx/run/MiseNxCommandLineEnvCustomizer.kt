package com.github.l34130.mise.nx.run

import com.github.l34130.mise.core.MiseHelper
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CommandLineEnvCustomizer
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Paths
import kotlin.io.path.pathString

private val NX_EXECUTABLES = listOf("nx", "nx.cmd")

@Suppress("UnstableApiUsage")
class MiseNxCommandLineEnvCustomizer : CommandLineEnvCustomizer {
    override fun customizeEnv(
        commandLine: GeneralCommandLine,
        environment: MutableMap<String, String>,
    ) {
        if (Paths.get(commandLine.exePath).fileName.toString() in NX_EXECUTABLES) {
            val workDir = commandLine.workingDirectory?.pathString ?: return

            // Locate project from working directory
            val vf = LocalFileSystem.getInstance().findFileByPath(workDir) ?: return
            val project = ProjectLocator.getInstance().guessProjectForFile(vf) ?: return

            val envvar = MiseHelper.getMiseEnvVarsOrNotify(project, workDir, null)
            environment.putAll(envvar)
        }
    }
}
