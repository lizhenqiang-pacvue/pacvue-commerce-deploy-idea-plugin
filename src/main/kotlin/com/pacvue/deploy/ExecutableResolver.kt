package com.pacvue.deploy

import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager
import java.io.File

internal object ExecutableResolver {
    const val GIT_PATH_ENV = "PACVUE_GIT_PATH"
    const val GH_PATH_ENV = "PACVUE_GH_PATH"

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    val macExecutableDirectories = listOf(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/opt/local/bin",
        "/usr/bin",
        "/bin",
    )

    fun windowsExecutableDirectories(): List<String> {
        val localAppData = System.getenv("LOCALAPPDATA")
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

        return listOfNotNull(
            "$programFiles\\Git\\cmd",
            "$programFiles\\Git\\bin",
            "$programFilesX86\\Git\\cmd",
            localAppData?.let { "$it\\Programs\\Git\\cmd" },
            localAppData?.let { "$it\\Programs\\Git\\bin" },
            "$programFiles\\GitHub CLI",
            localAppData?.let { "$it\\Programs\\GitHub CLI" },
        )
    }

    fun platformExecutableDirectories(): List<String> {
        return if (isWindows) windowsExecutableDirectories() else macExecutableDirectories
    }

    fun extendExecutablePath(pathValue: String?): String {
        return sequenceOf(
            pathValue.orEmpty().split(File.pathSeparator).asSequence(),
            platformExecutableDirectories().asSequence(),
        )
            .flatten()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(File.pathSeparator)
    }

    fun resolveExecutableCommand(
        command: String,
        pathValue: String? = System.getenv("PATH"),
        fileExists: (String) -> Boolean = { File(it).canExecute() },
    ): String {
        if (command.contains(File.separatorChar)) {
            return command
        }

        return searchExecutableInDirectories(
            command = command,
            directories = sequenceOf(
                pathValue.orEmpty().split(File.pathSeparator).asSequence(),
                platformExecutableDirectories().asSequence(),
            )
                .flatten()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            fileExists = fileExists,
        ) ?: command
    }

    fun resolveGitPath(project: Project? = null, workingDir: File = File(".")): String? {
        project?.let { currentProject ->
            runCatching {
                GitExecutableManager.getInstance()
                    .getExecutable(currentProject, workingDir)
                    .exePath
                    .takeIf { path -> path.isNotBlank() && File(path).exists() }
            }.getOrNull()?.let { return it }
        }

        return findSystemExecutable("git", workingDir)
    }

    fun resolveGhPath(workingDir: File = File(".")): String? {
        return findSystemExecutable("gh", workingDir)
    }

    fun applyChildProcessEnvironment(
        builder: ProcessBuilder,
        project: Project? = null,
        workingDir: File,
    ) {
        val environment = builder.environment()
        environment["PATH"] = extendExecutablePath(environment["PATH"])
        resolveGitPath(project, workingDir)?.let { environment[GIT_PATH_ENV] = it }
        resolveGhPath(workingDir)?.let { environment[GH_PATH_ENV] = it }
    }

    fun findSystemExecutable(
        command: String,
        workingDir: File,
    ): String? {
        if (File(command).exists() && File(command).canExecute()) {
            return File(command).absolutePath
        }

        if (isWindows) {
            runCatching {
                val process = ProcessBuilder("cmd", "/c", "where", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val status = process.waitFor()

                if (status == 0 && output.isNotBlank()) {
                    val fullPath = output.trim().lines().first().trim()
                    if (File(fullPath).exists()) {
                        return fullPath
                    }
                }
            }
        } else {
            runCatching {
                val process = ProcessBuilder("which", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val status = process.waitFor()

                if (status == 0 && output.isNotBlank()) {
                    val fullPath = output.trim()
                    if (File(fullPath).exists() && File(fullPath).canExecute()) {
                        return fullPath
                    }
                }
            }
        }

        return searchExecutableInDirectories(
            command = command,
            directories = sequenceOf(
                System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence(),
                platformExecutableDirectories().asSequence(),
            )
                .flatten()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
        )
    }

    private fun searchExecutableInDirectories(
        command: String,
        directories: Sequence<String>,
        fileExists: (String) -> Boolean = { candidate -> File(candidate).exists() && File(candidate).canExecute() },
    ): String? {
        for (directory in directories) {
            for (candidateName in executableCandidateNames(command)) {
                val candidate = File(directory, candidateName).absolutePath
                if (fileExists(candidate)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun executableCandidateNames(command: String): List<String> {
        if (!isWindows) {
            return listOf(command)
        }

        return when {
            command.endsWith(".exe", ignoreCase = true) ||
                command.endsWith(".cmd", ignoreCase = true) ||
                command.endsWith(".bat", ignoreCase = true) -> listOf(command)

            else -> listOf("$command.exe", "$command.cmd", "$command.bat", command)
        }
    }
}
