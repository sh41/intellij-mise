package com.github.l34130.mise.idea.jdk

import com.github.l34130.mise.core.command.MiseDevToolName
import com.github.l34130.mise.core.command.MiseDevTool
import com.github.l34130.mise.core.setup.AbstractProjectSdkSetup
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.reflect.KClass

class MiseProjectJdkSetup : AbstractProjectSdkSetup() {
    override fun getDevToolName(project: Project) = MiseDevToolName("java")

    override fun checkSdkStatus(
        tool: MiseDevTool,
        project: Project,
    ): SdkStatus {
        val currentSdk = ProjectRootManager.getInstance(project).projectSdk
        val newSdk = tool.asJavaSdk()

        if (currentSdk == null) {
            return SdkStatus.NeedsUpdate(
                currentSdkVersion = null,
            )
        }

        if (isSamePath(currentSdk.homePath, newSdk.homePath)) {
            return SdkStatus.UpToDate
        }

        val displayVersion =
            if (currentSdk.sdkType is JavaSdk) {
                val javaSdk = JavaSdk.getInstance()
                val canonicalName = currentSdk.homePath?.let { javaSdk.suggestSdkName(null, it) } ?: currentSdk.name
                val version = sanitizeVersion(javaSdk.getVersionString(currentSdk))
                if (version != null) {
                    "$canonicalName ($version)"
                } else {
                    canonicalName
                }
            } else {
                currentSdk.name
            }

        return SdkStatus.NeedsUpdate(
            currentSdkVersion = displayVersion,
        )
    }

    private fun sanitizeVersion(version: String?): String? {
        if (version == null) return null
        return version
            .replace("Oracle OpenJDK", "", ignoreCase = true)
            .replace("Azul Zulu", "", ignoreCase = true)
            .replace("java version", "", ignoreCase = true)
            .replace("\"", "")
            .replace(Regex("\\s*[-]?\\s*(aarch64|x86_64|x64|amd64)"), "")
            .trim()
    }

    override fun applySdkConfiguration(
        tool: MiseDevTool,
        project: Project,
    ) {
        WriteAction.computeAndWait<Unit, Throwable> {
            val projectJdkTable = ProjectJdkTable.getInstance()

            val sdk =
                tool.asJavaSdk().also { sdk ->
                    upsertJdk(projectJdkTable, sdk)
                }

            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
    }

    private fun upsertJdk(
        projectJdkTable: ProjectJdkTable,
        sdk: Sdk,
    ) {
        val existing = projectJdkTable.findJdk(sdk.name)
        if (existing != null) {
            projectJdkTable.updateJdk(existing, sdk)
            return
        }

        try {
            projectJdkTable.addJdk(sdk)
        } catch (e: RuntimeException) {
            // Workspace model can throw if another update added the same symbolic ID.
            val retry = projectJdkTable.findJdk(sdk.name)
            if (retry != null) {
                projectJdkTable.updateJdk(retry, sdk)
            } else {
                throw e
            }
        }
    }

    private fun isSamePath(path1: String?, path2: String?): Boolean {
        if (path1 == null || path2 == null) return false
        return FileUtil.filesEqual(File(path1), File(path2))
    }

    override fun <T : Configurable> getSettingsConfigurableClass(): KClass<out T>? = null

    private fun MiseDevTool.asJavaSdk(): Sdk {
        return JavaSdk.getInstance().createJdk(this.jdkName(), resolvedInstallPath, false)
    }

    private fun MiseDevTool.jdkName(): String = "${this.resolvedVersion} (mise)"

    companion object {
        private val logger = logger<MiseProjectJdkSetup>()
    }
}
