package com.github.l34130.mise.core.setup

import com.github.l34130.mise.core.command.MiseDevTool
import com.github.l34130.mise.core.command.MiseDevToolName
import com.github.l34130.mise.core.notification.MiseNotificationService
import com.intellij.notification.NotificationAction
import com.intellij.openapi.project.Project

// Applies SDK configuration and emits notifications for sync decisions.
internal class SdkConfigureCoordinator(
    private val settingsUpdater: SdkSetupSettingsUpdater = SdkSetupSettingsUpdater(),
) {
    fun applyIfNeeded(
        project: Project,
        provider: AbstractProjectSdkSetup,
        tool: MiseDevTool,
        devToolName: MiseDevToolName,
        displayName: String,
        isUserInteraction: Boolean,
        autoConfigureEnabled: Boolean,
        notificationService: MiseNotificationService,
    ) {
        try {
            val status = provider.checkSdkStatusInternal(tool, project)
            when (status) {
                is AbstractProjectSdkSetup.SdkStatus.NeedsUpdate -> {
                    val titleQualifier = formatTitleQualifier(status.currentSdkLocation)
                    val titleSuffix = titleQualifier?.let { " ($it)" }.orEmpty()
                    val title =
                        if (status.currentSdkVersion == null) {
                            "${devToolName.canonicalName()} Not Configured$titleSuffix"
                        } else {
                            "${devToolName.canonicalName()} Version Mismatch$titleSuffix"
                        }

                    val description =
                        if (status.currentSdkVersion == null) {
                            "Configure as '${devToolName.value}@${tool.displayVersion}'"
                        } else {
                            val currentLabel = formatLocationLabel(status.currentSdkLocation)
                            buildString {
                                append("$currentLabel: ${status.currentSdkVersion} <br/>")
                                append("Mise: <b>${devToolName.canonicalName()} ${tool.displayVersionWithResolved}</b>")
                            }
                        }

                    val applyAction: (Boolean) -> Unit = { isAuto ->
                        provider.applySdkConfigurationInternal(tool, project)
                        if (isAuto) {
                            notificationService.info(
                                "Auto-configured $displayName",
                                "Now using ${devToolName.value}@${tool.displayVersionWithResolved}",
                            )
                        } else {
                            notificationService.info(
                                "${devToolName.canonicalName()} is configured to ${tool.displayVersionWithResolved}",
                                ""
                            )
                        }
                    }

                    when {
                        isUserInteraction -> applyAction(false)
                        autoConfigureEnabled -> applyAction(true)
                        else -> {
                            notificationService.info(title, description) { notification ->
                                notification.addAction(
                                    NotificationAction.createSimpleExpiring("Configure now") {
                                        applyAction(false)
                                    }
                                )
                                notification.addAction(
                                    NotificationAction.createSimpleExpiring("Always keep $displayName in sync") {
                                        settingsUpdater.update(project, provider, autoConfigure = true)
                                        applyAction(true)
                                    }
                                )
                            }
                        }
                    }
                }
                AbstractProjectSdkSetup.SdkStatus.UpToDate -> {
                    if (!isUserInteraction) return

                    notificationService.info(
                        "${devToolName.canonicalName()} is up to date",
                        "Currently using ${devToolName.value}@${tool.displayVersion}",
                    )
                }
            }
        } catch (e: Throwable) {
            notificationService.error(
                "Failed to set ${devToolName.canonicalName()} to ${devToolName.value}@${tool.displayVersion}",
                e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun formatTitleQualifier(location: AbstractProjectSdkSetup.SdkLocation?): String? {
        return when (location) {
            AbstractProjectSdkSetup.SdkLocation.Project -> null
            AbstractProjectSdkSetup.SdkLocation.Setting -> "Setting"
            is AbstractProjectSdkSetup.SdkLocation.Module -> "Module: ${location.name}"
            is AbstractProjectSdkSetup.SdkLocation.Custom -> "Custom: ${location.label}"
            null -> null
        }
    }

    private fun formatLocationLabel(location: AbstractProjectSdkSetup.SdkLocation?): String {
        return when (location) {
            AbstractProjectSdkSetup.SdkLocation.Project, null -> "Project"
            AbstractProjectSdkSetup.SdkLocation.Setting -> "Setting"
            is AbstractProjectSdkSetup.SdkLocation.Module -> "Module: ${location.name}"
            is AbstractProjectSdkSetup.SdkLocation.Custom -> "Custom: ${location.label}"
        }
    }
}
