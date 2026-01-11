package com.github.l34130.mise.core.command

import com.github.l34130.mise.core.MiseHelper
import com.github.l34130.mise.core.setting.MiseExecutableManager
import com.github.l34130.mise.core.setting.MiseProjectSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CommandLineEnvCustomizer
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.LocalFileSystem
import kotlin.io.path.pathString

/**
 * Global command line customizer that injects mise environment variables
 * into ALL command line execution not already handled by specific customizers.
 *
 * This is a catch-all for terminal commands, external tools, build tools, etc.
 * Controlled by the "Use in all other command line execution" setting.
 *
 * Double-injection is prevented by checking for the marker added by
 * MiseHelper.getMiseEnvVarsOrNotify().
 */
@Suppress("UnstableApiUsage")
class MiseCommandLineEnvCustomizer : CommandLineEnvCustomizer {
    private val logger = Logger.getInstance(MiseCommandLineEnvCustomizer::class.java)

    override fun customizeEnv(
        commandLine: GeneralCommandLine,
        environment: MutableMap<String, String>,
    ) {
        // Skip if already injected by another customizer or told to skip by the caller.
        if (!MiseCommandLineHelper.environmentNeedsCustomization(environment)) {
            return
        }

        // Resolve project from working directory
        val workDirResolved = commandLine.workingDirectory?.pathString ?: return

        val vf = LocalFileSystem.getInstance().findFileByPath(workDirResolved) ?: return

        val project = ProjectLocator.getInstance().guessProjectForFile(vf) ?: return


        // Skip mise commands to prevent infinite recursion
        if (project.service<MiseExecutableManager>().matchesMiseExecutablePath(commandLine)) {
            return
        }

        // Check settings
        val settings = project.service<MiseProjectSettings>().state

        if (!settings.useMiseDirEnv || !settings.useMiseInAllCommandLines) {
            return
        }

        try {
            // 6. Inject mise environment variables
            val envVars = MiseHelper.getMiseEnvVarsOrNotify(
                project = project,
                workingDirectory = workDirResolved,
                configEnvironment = settings.miseConfigEnvironment
            )
            environment.putAll(envVars)
        } catch (e: Exception) {
            logger.error("  → ERROR: Failed to inject env vars", e)
            // Remove marker on failure so it doesn't block future attempts
            environment.remove(MiseCommandLineHelper.INJECTION_MARKER_KEY)
        }
    }
}
