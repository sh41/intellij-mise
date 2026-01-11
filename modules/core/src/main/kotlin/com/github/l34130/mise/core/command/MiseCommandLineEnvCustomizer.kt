package com.github.l34130.mise.core.command

import com.github.l34130.mise.core.MiseEnvCustomizer
import com.github.l34130.mise.core.setting.MiseProjectSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CommandLineEnvCustomizer
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Base command line customizer that customizes mise environment variables.
 *
 * This class can be extended by specific customizers (e.g., NX, Gradle) to filter
 * by executable name and customize settings checks.
 *
 * When used directly, it acts as a catch-all for terminal commands, external tools,
 * build tools, etc. Controlled by the "Use in all other command line execution" setting.
 *
 * Double-customization is prevented by checking for the marker added by
 * MiseHelper.getMiseEnvVarsOrNotify().
 */
@Suppress("UnstableApiUsage")
open class MiseCommandLineEnvCustomizer : CommandLineEnvCustomizer, MiseEnvCustomizer {
    override val logger = Logger.getInstance(MiseCommandLineEnvCustomizer::class.java)

    override fun customizeEnv(
        commandLine: GeneralCommandLine,
        environment: MutableMap<String, String>,
    ) {
        // 1. Safe exePath check
        if (MiseCommandLineHelper.safeGetExePath(commandLine) == null) return

        // 2. Resolve the project from the command line
        val project = MiseCommandLineHelper.resolveProjectFromCommandLine(commandLine) ?: return

        // 3. Check if we should customize for this project (can be overridden by subclasses)
        if (!shouldCustomizeForProject(project, commandLine)) return

        // 4. Resolve the working directory (fallback to the project path)
        val workDir = MiseCommandLineHelper.resolveWorkingDirectory(commandLine, project)

        // 5. Shared customization logic (marker check, settings check, customize with error handling)
        customizeMiseEnvironment(project, workDir, environment)
    }

    /**
     * Hook for subclasses to check if mise commands should be skipped.
     * Default: skip mise commands to prevent infinite recursion.
     * Subclasses can override to skip this check if already filtered by executable name.
     */
    protected open fun shouldCustomizeForProject(project: Project, commandLine: GeneralCommandLine): Boolean {
        return !project.service<MiseExecutableManager>().matchesMiseExecutablePath(commandLine)
    }

    /**
     * Hook for subclasses to customize settings validation.
     * Default: check master toggle and "use in all command lines" setting.
     * Subclasses override this to check their specific setting flag.
     */
    override fun shouldCustomizeForSettings(settings: MiseProjectSettings.MyState): Boolean {
        return settings.useMiseDirEnv && settings.useMiseInAllCommandLines
    }
}
