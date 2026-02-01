package com.github.l34130.mise.core.setup

import com.github.l34130.mise.core.command.MiseDevTool
import com.github.l34130.mise.core.command.MiseDevToolName
import com.github.l34130.mise.core.setting.MiseProjectSettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.extensions.ExtensionPointName
import kotlin.reflect.KClass

abstract class AbstractProjectSdkSetup :
    DumbAwareAction() {
    final override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { configureSdk(it, true) }
    }

    abstract fun getDevToolName(project: Project): MiseDevToolName

    open fun getSettingsId(project: Project): String = javaClass.name

    open fun getSettingsDisplayName(project: Project): String = getDevToolName(project).canonicalName()

    open fun shouldAutoConfigure(project: Project): Boolean = true

    open fun shouldAutoInstall(project: Project): Boolean = false

    fun isAutoConfigureEnabled(project: Project): Boolean {
        val settings = project.service<MiseProjectSettings>()
        val effective =
            settings.effectiveSdkSetupOption(
                id = getSettingsId(project),
                defaultAutoInstall = shouldAutoInstall(project),
                defaultAutoConfigure = shouldAutoConfigure(project),
            )
        return effective.autoConfigure
    }

    fun isAutoInstallEnabled(project: Project): Boolean {
        val settings = project.service<MiseProjectSettings>()
        val effective =
            settings.effectiveSdkSetupOption(
                id = getSettingsId(project),
                defaultAutoInstall = shouldAutoInstall(project),
                defaultAutoConfigure = shouldAutoConfigure(project),
            )
        return effective.autoInstall
    }

    protected abstract fun checkSdkStatus(
        tool: MiseDevTool,
        project: Project,
    ): SdkStatus

    protected abstract fun applySdkConfiguration(
        tool: MiseDevTool,
        project: Project,
    ): ApplySdkResult

    abstract fun <T : Configurable> getConfigurableClass(): KClass<out T>?

    fun configureSdk(
        project: Project,
        isUserInteraction: Boolean,
    ) {
        // Delegates to a coordinator so providers only implement the SDK-specific hooks.
        coordinator.run(project, this, isUserInteraction)
    }

    internal fun checkSdkStatusInternal(
        tool: MiseDevTool,
        project: Project,
    ): SdkStatus = checkSdkStatus(tool, project)

    internal fun applySdkConfigurationInternal(
        tool: MiseDevTool,
        project: Project,
    ): ApplySdkResult = applySdkConfiguration(tool, project)

    internal fun configurableClass(): KClass<out Configurable>? = getConfigurableClass<Configurable>()

    sealed interface SdkStatus {
        data class NeedsUpdate(
            val currentSdkVersion: String?,
            val requestedInstallPath: String,
        ) : SdkStatus

        object UpToDate : SdkStatus
    }

    data class ApplySdkResult(
        val sdkName: String,
        val sdkVersion: String,
        val sdkPath: String,
    )

    companion object {
        val EP_NAME = ExtensionPointName.create<AbstractProjectSdkSetup>("com.github.l34130.mise.projectSdkSetup")
        private val coordinator = SdkSetupCoordinator()

        fun runAll(project: Project, isUserInteraction: Boolean) {
            EP_NAME.extensionList.forEach { provider ->
                provider.configureSdk(project, isUserInteraction)
            }
        }
    }
}
