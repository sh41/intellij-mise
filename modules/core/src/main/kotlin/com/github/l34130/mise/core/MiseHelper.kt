package com.github.l34130.mise.core

import com.github.l34130.mise.core.command.MiseCommandLineHelper
import com.github.l34130.mise.core.command.MiseCommandLineNotFoundException
import com.github.l34130.mise.core.notification.MiseNotificationServiceUtils
import com.github.l34130.mise.core.run.ConfigEnvironmentStrategy
import com.github.l34130.mise.core.run.MiseRunConfigurationSettingsEditor
import com.github.l34130.mise.core.setting.MiseProjectSettings
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object MiseHelper {
    fun getMiseEnvVarsOrNotify(
        configuration: RunConfigurationBase<*>,
        workingDirectory: String?,
    ): Map<String, String> {
        val project = configuration.project
        val projectState = project.service<MiseProjectSettings>().state
        val runConfigState = MiseRunConfigurationSettingsEditor.getMiseRunConfigurationState(configuration)

        // Check if disabled at run config level
        if (runConfigState?.useMiseDirEnv == false) return emptyMap()

        // Check master toggle AND run config setting
        if (!projectState.useMiseDirEnv || !projectState.useMiseInRunConfigurations) {
            return emptyMap()
        }

        val workDir = workingDirectory?.takeIf { it.isNotBlank() }
            ?: project.guessMiseProjectPath()

        val configEnvironment =
            if (runConfigState?.configEnvironmentStrategy == ConfigEnvironmentStrategy.OVERRIDE_PROJECT_SETTINGS) {
                runConfigState.miseConfigEnvironment
            } else {
                projectState.miseConfigEnvironment
            }

        return getMiseEnvVarsOrNotify(project, workDir, configEnvironment)
    }

    fun getMiseEnvVarsOrNotify(
        project: Project,
        workingDirectory: String,
        configEnvironment: String? = null,
    ): Map<String, String> {
        val projectState = project.service<MiseProjectSettings>().state

        val useMiseDirEnv = projectState.useMiseDirEnv
        if (!useMiseDirEnv) {
            logger.debug { "Mise environment variables loading is disabled in project settings" }
            return emptyMap()
        }

        val configEnvironment = configEnvironment ?: projectState.miseConfigEnvironment

        val result =
            if (application.isDispatchThread) {
                logger.debug { "dispatch thread detected, loading env vars on current thread" }
                runWithModalProgressBlocking(project, "Loading Mise Environment Variables") {
                    MiseCommandLineHelper.getEnvVars(project, workingDirectory, configEnvironment)
                }
            } else if (!application.isReadAccessAllowed) {
                logger.debug { "no read lock detected, loading env vars on dispatch thread" }
                var result: Result<Map<String, String>>? = null
                application.invokeAndWait {
                    logger.debug { "loading env vars on invokeAndWait" }
                    runWithModalProgressBlocking(project, "Loading Mise Environment Variables") {
                        result = MiseCommandLineHelper.getEnvVars(project, workingDirectory, configEnvironment)
                    }
                }
                result ?: throw ProcessCanceledException()
            } else {
                logger.debug { "read access allowed, executing on background thread" }
                runBlocking(Dispatchers.IO) {
                    MiseCommandLineHelper.getEnvVars(project, workingDirectory, configEnvironment)
                }
            }

        return result
            .fold(
                onSuccess = { envVars ->
                    // Add injection marker to prevent double-injection by other customizers
                    envVars + (MiseCommandLineHelper.INJECTION_MARKER_KEY to MiseCommandLineHelper.INJECTION_MARKER_VALUE)
                },
                onFailure = {
                    if (it !is MiseCommandLineNotFoundException) {
                        MiseNotificationServiceUtils.notifyException("Failed to load environment variables", it, project)
                    }
                    mapOf()
                },
            )
    }

    private val logger = Logger.getInstance(MiseHelper::class.java)
}
