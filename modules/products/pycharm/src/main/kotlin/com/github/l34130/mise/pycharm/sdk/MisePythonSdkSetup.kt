package com.github.l34130.mise.pycharm.sdk

import com.github.l34130.mise.core.command.MiseCommandLineHelper
import com.github.l34130.mise.core.command.MiseDevTool
import com.github.l34130.mise.core.command.MiseDevToolName
import com.github.l34130.mise.core.setting.MiseProjectSettings
import com.github.l34130.mise.core.setup.AbstractProjectSdkSetup
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PlatformUtils
import com.jetbrains.python.PythonModuleTypeBase
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.PythonSdkUtil
import kotlin.reflect.KClass

class MisePythonSdkSetup : AbstractProjectSdkSetup() {
    override fun getDevToolName(project: Project): MiseDevToolName = MiseDevToolName("python")

    override fun defaultAutoConfigure(project: Project): Boolean = false

    override fun checkSdkStatus(
        tool: MiseDevTool,
        project: Project,
    ): SdkStatus {
        checkUvEnabled(project)

        val desiredHomePath = tool.resolvePythonPath(project)
        val targetModules = resolveTargetModules(project)

        if (logger.isTraceEnabled) {
            val moduleNames = targetModules.joinToString { it.name }
            logger.trace("Python SDK check: desiredHomePath='$desiredHomePath', targetModules=[$moduleNames]")
        }

        if (targetModules.isEmpty()) {
            if (logger.isTraceEnabled) {
                logger.trace("Python SDK check: no target modules found.")
            }
            return SdkStatus.NeedsUpdate(currentSdkVersion = null)
        }

        val mismatchModule =
            ReadAction.compute<Module?, Throwable> {
                targetModules.firstOrNull { module ->
                    val sdk = resolveModulePythonSdk(module)
                    val sdkHomePath = sdk?.homePath
                    val matches = sdkHomePath != null && FileUtil.pathsEqual(sdkHomePath, desiredHomePath)
                    if (logger.isTraceEnabled) {
                        logger.trace(
                            "Python SDK module check: module='${module.name}', " +
                                "sdk='${sdk?.name}', sdkHomePath='$sdkHomePath', matches=$matches"
                        )
                    }
                    !matches
                }
            }

        if (mismatchModule != null) {
            val currentSdk =
                ReadAction.compute<Sdk?, Throwable> {
                    resolveModulePythonSdk(mismatchModule)
                }
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
        val newSdk = tool.ensureUvSdk(project)
        val targetModules = resolveTargetModules(project)
        if (targetModules.isEmpty()) return

        if (logger.isTraceEnabled) {
            val moduleNames = targetModules.joinToString { it.name }
            logger.trace("Applying Python SDK '${newSdk.name}' to modules=[$moduleNames]")
        }

        val shouldSetProjectSdk = PlatformUtils.isPyCharm() || PlatformUtils.isDataSpell()
        WriteAction.computeAndWait<Unit, Throwable> {
            if (shouldSetProjectSdk) {
                ProjectRootManager.getInstance(project).projectSdk = newSdk
            }
            targetModules.forEach { module ->
                ModuleRootModificationUtil.setModuleSdk(module, newSdk)
            }
        }
    }

    override fun <T : Configurable> getSettingsConfigurableClass(): KClass<out T>? = null

    private fun checkUvEnabled(project: Project) {
        val configEnvironment = project.service<MiseProjectSettings>().state.miseConfigEnvironment
        val useUv =
            // Check if the 'settings.python.uv_venv_auto' is set to true
            MiseCommandLineHelper
                .getConfig(project, project.guessMiseProjectPath(), configEnvironment, "settings.python.uv_venv_auto")
                .getOrNull()
                ?.trim()
                ?.toBoolean() ?: false

        if (!useUv) {
            throw UnsupportedOperationException("Mise Python SDK setup requires 'settings.python.uv_venv_auto' to be true.")
        }
    }
}

private fun MiseDevTool.resolvePythonPath(project: Project): String {
    return MiseCommandLineHelper.getBinPath("python", project)
        .getOrElse { throw IllegalStateException("Failed to find Python executable ($resolvedInstallPath): ${it.message}", it) }
}

private fun MiseDevTool.ensureUvSdk(project: Project): Sdk {
    val sdkName = uvSdkName()
    val sdk = ProjectJdkImpl(
        sdkName,
        PythonSdkType.getInstance(),
        resolvePythonPath(project),
        resolvedVersion,
    )

    val registeredSdk =
        WriteAction.computeAndWait<Sdk, Throwable> {
        val table = ProjectJdkTable.getInstance()
        val existing = PythonSdkUtil.getAllSdks().firstOrNull { it.name == sdkName }
        if (existing == null) {
            table.addJdk(sdk)
            sdk
        } else {
            table.updateJdk(existing, sdk)
            existing
        }
    }

    PythonSdkType.getInstance().setupSdkPaths(registeredSdk)
    return registeredSdk
}

private fun resolveTargetModules(project: Project): List<Module> {
    return ReadAction.compute<List<Module>, Throwable> {
        val modules = ModuleManager.getInstance(project).modules.toList()
        if (modules.isEmpty()) return@compute emptyList()

        val pythonModules = modules.filter { ModuleType.get(it) is PythonModuleTypeBase<*> }
        if (pythonModules.isNotEmpty()) return@compute pythonModules

        val configuredModules = modules.filter { resolveModulePythonSdk(it) != null }
        if (configuredModules.isNotEmpty()) return@compute configuredModules

        if (modules.size == 1) return@compute modules

        val basePath = project.basePath ?: return@compute emptyList()
        val baseModule =
            modules.firstOrNull { module ->
                ModuleRootManager.getInstance(module).contentRoots.any { it.path == basePath }
            }
        baseModule?.let { listOf(it) } ?: emptyList()
    }
}

private fun resolveModulePythonSdk(module: Module): Sdk? {
    val moduleSdk = ModuleRootManager.getInstance(module).sdk
    if (moduleSdk != null && PythonSdkUtil.isPythonSdk(moduleSdk)) return moduleSdk
    return PythonSdkUtil.findPythonSdk(module)
}

private fun MiseDevTool.uvSdkName(): String {
    val displayVersion = this.displayVersion
    return if (displayVersion.isBlank()) {
        "uv (python)"
    } else {
        "uv (python $displayVersion)"
    }
}

private val logger = logger<MisePythonSdkSetup>()
