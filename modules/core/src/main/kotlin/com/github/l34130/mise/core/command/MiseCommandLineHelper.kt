package com.github.l34130.mise.core.command

import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

object MiseCommandLineHelper {
    /**
     * Marker added to environment variables to prevent double-injection in the case where
     * multiple env customizers are called.
     * This marker is checked by all customizers to skip injection if already done.
     */
    const val INJECTION_MARKER_KEY = "_MISE_PLUGIN_ENV_VARS_CUSTOMIZATION"
    const val INJECTION_MARKER_VALUE_DONE = "done"
    const val INJECTION_MARKER_VALUE_SKIP = "skipped"

    /**
     * Check if the mise plugin needs to customize environment variables.
     * @return true if customization is required, false otherwise
     */
    fun environmentNeedsCustomization(environment: Map<String, String>): Boolean {
        return !environment.containsKey(INJECTION_MARKER_KEY) ||
                (
                        environment[INJECTION_MARKER_KEY] != INJECTION_MARKER_VALUE_DONE
                                && environment[INJECTION_MARKER_KEY] != INJECTION_MARKER_VALUE_SKIP
                        )
    }

    /**
     * Add injection marker to environment to prevent double-injection. Used by the VCS customizer
     * This marker is checked by all customizers to skip injection if already done.
     */
    fun environmentHasBeenCustomizedNullable(environment: MutableMap<String?, String?>) {
        environment[INJECTION_MARKER_KEY] = INJECTION_MARKER_VALUE_DONE
    }
    /**
     * Add injection marker to environment to prevent double-injection. Used by the GeneralCommandLine customizer
     * This marker is checked by all customizers to skip injection if already done.
     */
    fun environmentHasBeenCustomized(environment: MutableMap<String, String>) {
        environment[INJECTION_MARKER_KEY] = INJECTION_MARKER_VALUE_DONE
    }

    /**
     * Add injection marker to environment to prevent double-injection.
     * This marker is checked by all customizers to skip injection if already done.
     */
    fun environmentSkipCustomization(environment: MutableMap<String?, String?>) {
        environment[INJECTION_MARKER_KEY] = INJECTION_MARKER_VALUE_SKIP
    }

    // mise env
    @RequiresBackgroundThread
    fun getEnvVars(
        project: Project,
        workDir: String = project.guessMiseProjectPath(),
        configEnvironment: String? = null,
    ): Result<Map<String, String>> {
        val cache = project.service<MiseCommandCache>()
        return cache.getCached(
            key = "env:$workDir:$configEnvironment"
        ) {
            val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
            miseCommandLine.runCommandLine(listOf("env", "--json"))
        }
    }

    // mise env
    @RequiresBackgroundThread
    fun getEnvVarsExtended(
        project: Project,
        workDir: String = project.guessMiseProjectPath(),
        configEnvironment: String? = null,
    ): Result<Map<String, MiseEnvExtended>> {
        val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)

        val envs =
            miseCommandLine
                .runCommandLine<Map<String, MiseEnv>>(listOf("env", "--json-extended"))
                .getOrElse { return Result.failure(it) }

        val redactedEnvKeys =
            miseCommandLine
                .runCommandLine<Map<String, String>>(listOf("env", "--json", "--redacted"))
                .getOrElse { emptyMap() }
                .keys

        val extendedEnvs =
            envs.mapValues { (key, env) ->
                MiseEnvExtended(
                    value = env.value,
                    source = env.source,
                    tool = env.tool,
                    redacted = redactedEnvKeys.contains(key),
                )
            }

        return Result.success(extendedEnvs)
    }


    // mise ls
    @RequiresBackgroundThread
    fun getDevTools(
        project: Project,
        workDir: String = project.guessMiseProjectPath(),
        configEnvironment: String? = null,
    ): Result<Map<MiseDevToolName, List<MiseDevTool>>> {
        val cache = project.service<MiseCommandCache>()
        return cache.getCached(
            key = "ls:$workDir:$configEnvironment"
        ) {
            val commandLineArgs = mutableListOf("ls", "--local", "--json")

            val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
            miseCommandLine
                .runCommandLine<Map<String, List<MiseDevTool>>>(commandLineArgs)
                .map { devTools ->
                    devTools.mapKeys { (toolName, _) -> MiseDevToolName(toolName) }
                }
        }
    }

    // mise task ls
    @RequiresBackgroundThread
    fun getTasks(
        project: Project,
        workDir: String,
        configEnvironment: String?,
    ): Result<List<MiseTask>> {
        val cache = project.service<MiseCommandCache>()
        return cache.getCached(
            key = "tasks:$workDir:$configEnvironment"
        ) {
            val commandLineArgs = mutableListOf("task", "ls", "--json")

            val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
            miseCommandLine.runCommandLine(commandLineArgs)
        }
    }

    // mise config get
    @RequiresBackgroundThread
    fun getConfig(
        project: Project,
        workDir: String?,
        configEnvironment: String?,
        key: String,
    ): Result<String> {
        val commandLineArgs = mutableListOf("config", "get", key)

        val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
        return miseCommandLine.runRawCommandLine(commandLineArgs)
    }

    // mise config --tracked-configs
    @RequiresBackgroundThread
    fun getTrackedConfigs(
        project: Project,
        configEnvironment: String,
    ): Result<List<String>> {
        val commandLineArgs = mutableListOf("config", "--tracked-configs")

        // Use the project's base path as the working directory to ensure correct mise context
        // (Windows mise for Windows projects, WSL mise for WSL projects)
        val workDir = project.guessMiseProjectPath()

        val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
        return miseCommandLine
            .runRawCommandLine(commandLineArgs)
            .map { it.lines().map { line -> line.trim() }.filter { trimmed -> trimmed.isNotEmpty() } }
    }

    // mise exec
    @RequiresBackgroundThread
    fun executeCommand(
        project: Project,
        workDir: String?,
        configEnvironment: String?,
        command: List<String>,
    ): Result<String> {
        val commandLineArgs = mutableListOf("exec", "--") + command

        val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
        return miseCommandLine.runRawCommandLine(commandLineArgs)
    }

    // mise trust
    @RequiresBackgroundThread
    fun trustConfigFile(
        project: Project,
        configFilePath: String,
        configEnvironment: String,
    ): Result<Unit> {
        val commandLineArgs = mutableListOf("trust", configFilePath)

        // Use the project's base path as the working directory to ensure correct mise context
        // (Windows mise for Windows projects, WSL mise for WSL projects)
        val workDir = project.guessMiseProjectPath()

        val miseCommandLine = MiseCommandLine(project, workDir, configEnvironment)
        return miseCommandLine.runCommandLine(commandLineArgs)
    }
}
