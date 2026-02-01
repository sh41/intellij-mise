package com.github.l34130.mise.ruby.sdk

import com.github.l34130.mise.core.command.MiseDevTool
import com.github.l34130.mise.core.command.MiseDevToolName
import com.github.l34130.mise.core.setup.AbstractProjectSdkSetup
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ProjectRootManager
import org.jetbrains.plugins.ruby.ruby.sdk.RubySdkType
import kotlin.reflect.KClass

class MiseRubyProjectSdkSetup : AbstractProjectSdkSetup() {
    override fun getDevToolName(project: Project): MiseDevToolName = MiseDevToolName("ruby")

    override fun defaultAutoConfigure(project: Project): Boolean = false

    override fun checkSdkStatus(
        tool: MiseDevTool,
        project: Project,
    ): SdkStatus {
        val currentSdk: Sdk? =
            ReadAction.compute<Sdk?, Throwable> {
                ProjectRootManager.getInstance(project).projectSdk
            }
        val newSdk = tool.asRubySdk()

        if (currentSdk == null || currentSdk.name != newSdk.name && currentSdk.homePath != newSdk.homePath) {
            return SdkStatus.NeedsUpdate(
                currentSdkVersion = currentSdk?.versionString,
            )
        }

        return SdkStatus.UpToDate
    }

    override fun applySdkConfiguration(
        tool: MiseDevTool,
        project: Project,
    ) {
        WriteAction.computeAndWait<Unit, Throwable> {
            val sdk =
                tool.asRubySdk().also { sdk ->
                    val exists = RubySdkType.getAllValidRubySdks().firstOrNull { it.name == sdk.name }
                    if (exists == null) {
                        ProjectJdkTable.getInstance().addJdk(sdk)
                    } else {
                        ProjectJdkTable.getInstance().updateJdk(exists, sdk)
                    }
                }

            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
    }

    // Application configurable
    // RubyDefaultProjectSdkGemsConfigurable::class as KClass<out T>
    override fun <T : Configurable> getSettingsConfigurableClass(): KClass<out T>? = null

    private fun MiseDevTool.asRubySdk(): Sdk {
        val sdkPath = resolvedInstallPath
        return ProjectJdkImpl(
            "mise: ${this.resolvedVersion}",
            RubySdkType.getInstance(),
            sdkPath,
            this.resolvedVersion,
        )
    }

    companion object {
        private val logger = logger<MiseRubyProjectSdkSetup>()
    }
}
