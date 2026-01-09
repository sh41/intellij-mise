package com.github.l34130.mise.core.command

import com.fasterxml.jackson.core.type.TypeReference
import com.github.l34130.mise.core.setting.MiseExecutableManager
import com.github.l34130.mise.core.util.guessMiseProjectPath
import com.github.l34130.mise.core.wsl.WslCommandHelper
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class MiseCommandLine(
    private val project: Project,
    workDir: String? = null,
    private val configEnvironment: String? = null,
) {
    private val workDir: String = workDir?.takeIf { it.isNotBlank() } ?: project.guessMiseProjectPath()
    private val commandCache = project.service<MiseCommandCache>()

    @RequiresBackgroundThread
    inline fun <reified T> runCommandLine(params: List<String>): Result<T> {
        val typeReference = object : TypeReference<T>() {}
        return runCommandLine(params, typeReference)
    }

    suspend inline fun <reified T> runCommandLineAsync(params: List<String>): Result<T> {
        val typeReference = object : TypeReference<T>() {}
        return runCommandLineAsync(params, typeReference)
    }

    @RequiresBackgroundThread
    inline fun <reified T> runCommandLine(
        params: List<String>,
        typeReference: TypeReference<T>,
    ): Result<T> {
        val rawResult = runRawCommandLine(params)
        return rawResult.fold(
            onSuccess = { output ->
                if (T::class == Unit::class) {
                    Result.success(Unit as T)
                } else {
                    Result.success(MiseCommandLineOutputParser.parse(output, typeReference))
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend inline fun <reified T> runCommandLineAsync(
        params: List<String>,
        typeReference: TypeReference<T>,
    ): Result<T> =
        withContext(Dispatchers.IO) {
            val rawResult = runRawCommandLine(params)
            rawResult.fold(
                onSuccess = { output ->
                    if (T::class == Unit::class) {
                        Result.success(Unit as T)
                    } else {
                        Result.success(MiseCommandLineOutputParser.parse(output, typeReference))
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }

    @RequiresBackgroundThread
    fun runRawCommandLine(params: List<String>): Result<String> {
        logger.debug("==> [COMMAND] Starting command execution (workDir: $workDir, params: $params)")

        val miseVersion = getMiseVersion()

        // Determine executable path with project override support
        val executablePath = determineExecutablePath()

        // Build command line arguments
        val commandLineArgs = mutableListOf<String>()

        // Handle executable path (may contain spaces like "wsl.exe -d Ubuntu mise")
        commandLineArgs.addAll(ParametersListUtil.parse(executablePath))

        // Add mise configuration environment parameter
        if (!configEnvironment.isNullOrBlank()) {
            if (miseVersion >= MiseVersion(2024, 12, 2)) {
                commandLineArgs.add("--env")
                commandLineArgs.add(configEnvironment)
            } else {
                commandLineArgs.add("--profile")
                commandLineArgs.add(configEnvironment)
            }
        }

        // Transform path parameters if in WSL context
        val transformedParams = transformPathParameters(params, executablePath)
        commandLineArgs.addAll(transformedParams)

        // Let GeneralCommandLine handle WSL execution via Eel
        return runCommandLineInternal(commandLineArgs, workDir)
    }

    /**
     * Determine which mise executable to use.
     * Delegates to MiseExecutableManager which is the single source of truth.
     */
    private fun determineExecutablePath(): String {
        val executableManager = project.service<MiseExecutableManager>()
        val path = executableManager.getExecutablePath(workDir)
        logger.debug("==> [EXECUTABLE] Using path: $path (workDir: $workDir)")
        return path
    }

    /**
     * Transform path parameters from UNC to POSIX if needed.
     *
     * When executing in WSL, mise receives Windows UNC paths from IntelliJ,
     * but mise expects POSIX paths. We need to convert path arguments.
     */
    private fun transformPathParameters(params: List<String>, executablePath: String): List<String> {
        // Check if we're in WSL context
        val execIsWslUnc = WslCommandHelper.isWslUncPath(executablePath)
        val workDirIsWslUnc = workDir.isNotBlank() && WslCommandHelper.isWslUncPath(workDir)
        val execContainsWsl = executablePath.contains("wsl.exe", ignoreCase = true) ||
                              executablePath.startsWith("wsl ", ignoreCase = true)

        val isWslContext = execIsWslUnc || workDirIsWslUnc || execContainsWsl

        logger.debug("==> [PATH TRANSFORM] isWslContext: $isWslContext (exec: $executablePath, workDir: $workDir)")

        if (!isWslContext) {
            return params  // No transformation needed for Windows native
        }

        // Transform parameters that look like paths
        val transformed = params.map { param ->
            // Heuristic: if it contains path separators, try to convert it
            if (param.contains('\\') || (param.contains('/') && param.length > 3)) {
                val converted = WslCommandHelper.convertPathParameterForWsl(param)
                if (converted != param) {
                    logger.info("==> [PATH TRANSFORM] Converted: '$param' -> '$converted'")
                }
                converted
            } else {
                param
            }
        }

        return transformed
    }

    @RequiresBackgroundThread
    private fun runCommandLineInternal(
        commandLineArgs: List<String>,
        workingDir: String = this.workDir,
    ): Result<String> {
        val generalCommandLine = GeneralCommandLine(commandLineArgs).withWorkDirectory(workingDir.ifBlank { null })
        return runCommandLineInternal(generalCommandLine)
    }

    @RequiresBackgroundThread
    private fun runCommandLineInternal(
        generalCommandLine: GeneralCommandLine,
    ): Result<String> {
        logger.debug("==> [EXEC] ${generalCommandLine.commandLineString} (workDir: ${generalCommandLine.workDirectory})")

        val processOutput =
            try {
                ExecUtil.execAndGetOutput(generalCommandLine, 3000)
            } catch (e: ExecutionException) {
                logger.info("Failed to execute command. (command=$generalCommandLine)", e)
                return Result.failure(
                    MiseCommandLineNotFoundException(
                        generalCommandLine,
                        e.message ?: "Failed to execute command.",
                        e,
                    ),
                )
            }

        if (!processOutput.isExitCodeSet) {
            when {
                processOutput.isTimeout -> {
                    return Result.failure(Throwable("Command timed out. (command=$generalCommandLine)"))
                }

                processOutput.isCancelled -> {
                    return Result.failure(Throwable("Command was cancelled. (command=$generalCommandLine)"))
                }
            }
        }

        if (processOutput.exitCode != 0) {
            val stderr = processOutput.stderr
            val parsedError = MiseCommandLineException.parseFromStderr(generalCommandLine, stderr)
            if (parsedError == null) {
                logger.info("Failed to parse error from stderr. (stderr=$stderr)")
                return Result.failure(Throwable(stderr))
            } else {
                logger.debug("Parsed error from stderr. (error=$parsedError)")
                return Result.failure(parsedError)
            }
        }

        logger.debug("Command executed successfully. (command=$generalCommandLine)")
        return Result.success(processOutput.stdout)
    }

    @RequiresBackgroundThread
    private fun getMiseVersion(): MiseVersion {
        val executablePath = determineExecutablePath()

        return commandCache.getCachedBlocking(
            key = "version:$executablePath"
        ) {
            logger.info("==> [VERSION CHECK] Fetching mise version")

            val command = ParametersListUtil.parse(executablePath).toMutableList()
            command.add("version")

            runCommandLineInternal(command).map { MiseVersion.parse(it) }
        }.getOrElse { MiseVersion(0, 0, 0) }
    }


    companion object {
        private val logger = Logger.getInstance(MiseCommandLine::class.java)
    }
}
