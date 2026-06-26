package com.pacvue.deploy

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files

data class WorkflowInput(
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val description: String? = null,
    val type: String? = null,
    val default: Any? = null,
)

data class WorkflowMetadata(
    val file: String = "",
    val name: String = "",
    val hasWorkflowDispatch: Boolean = false,
    val isDefaultDeployWorkflow: Boolean = false,
    val branchInputName: String? = null,
    val inputs: Map<String, WorkflowInput> = emptyMap(),
)

data class WorkflowMetadataResponse(
    val ok: Boolean = false,
    val repoRoot: String? = null,
    val workflows: List<WorkflowMetadata> = emptyList(),
)

data class DeployCommandResult(
    val status: Int,
    val stdout: String,
    val stderr: String,
    val parsed: JsonObject?,
    val commandPreview: String,
    val run: WorkflowRunStatus? = null,
)

data class WorkflowRunStatus(
    val databaseId: Long? = null,
    val status: String? = null,
    val conclusion: String? = null,
    val displayTitle: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val url: String? = null,
)

class DeployService(private val project: Project) {
    private val gson = Gson()

    val projectRoot: File
        get() = File(project.basePath ?: System.getProperty("user.home"))

    fun getRepoId(): String {
        val remoteUrl = runCatching {
            runProcess("git", listOf("config", "--get", "remote.origin.url"), projectRoot).stdout.trim()
        }.getOrDefault("")

        return parseGithubRepoId(remoteUrl) ?: projectRoot.canonicalPath
    }

    fun getCurrentBranch(): String {
        return runProcess("git", listOf("branch", "--show-current"), projectRoot).stdout.trim()
    }

    fun getBranchOptions(currentBranch: String): List<String> {
        val localBranches = runProcess("git", listOf("branch", "--format=%(refname:short)"), projectRoot).stdout.lines()
        val remoteBranches = runProcess("git", listOf("branch", "-r", "--format=%(refname:short)"), projectRoot).stdout.lines()
        return sequenceOf(listOf(currentBranch), localBranches, remoteBranches.map { it.removePrefix("origin/") })
            .flatten()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "origin/HEAD" && !it.contains("HEAD ->") }
            .distinct()
            .toList()
    }

    fun getWorkflowMetadata(): WorkflowMetadataResponse {
        val script = findDeployScript()
        val result = runProcess("node", listOf(script.absolutePath, "--list-workflows-json"), projectRoot)
        return gson.fromJson(result.stdout, WorkflowMetadataResponse::class.java)
    }

    fun runDeploy(branch: String, workflow: String, inputs: Map<String, String>, dispatch: Boolean): DeployCommandResult {
        val script = findDeployScript()
        val args = mutableListOf(script.absolutePath, "--branch", branch, "--skip-last-run-inputs")
        if (workflow.isNotBlank()) {
            args.add("--workflow")
            args.add(workflow)
        }
        inputs
            .filterValues { it.isNotBlank() }
            .forEach { (key, value) ->
                args.add("--input")
                args.add("$key=$value")
            }
        args.add(if (dispatch) "--dispatch" else "--dry-run")

        val result = runProcess("node", args, projectRoot)
        val parsed = parseJson(result.stdout)
        return DeployCommandResult(
            status = result.status,
            stdout = result.stdout,
            stderr = result.stderr,
            parsed = parsed,
            commandPreview = listOf("node", *args.toTypedArray()).joinToString(" "),
            run = parseWorkflowRun(parsed),
        )
    }

    fun getRunStatus(runId: Long): WorkflowRunStatus {
        val result = runProcess(
            "gh",
            listOf(
                "run",
                "view",
                runId.toString(),
                "--json",
                "databaseId,status,conclusion,displayTitle,createdAt,updatedAt,url",
            ),
            projectRoot,
        )
        if (result.status != 0) {
            error(listOf(result.stderr, result.stdout).filter { it.isNotBlank() }.joinToString("\n").ifBlank {
                "Failed to load workflow run status."
            })
        }
        return gson.fromJson(result.stdout, WorkflowRunStatus::class.java)
    }

    fun cancelRun(runId: Long): ProcessResult {
        return runProcess("gh", listOf("run", "cancel", runId.toString()), projectRoot)
    }

    private fun findDeployScript(): File {
        val resource = javaClass.classLoader.getResourceAsStream("scripts/deploy-to-test.js")
        if (resource != null) {
            val tempScript = Files.createTempFile("pacvue-deploy-", ".js").toFile()
            resource.use { input ->
                tempScript.outputStream().use { output -> input.copyTo(output) }
            }
            tempScript.deleteOnExit()
            return tempScript
        }

        val externalCandidates = listOf(
            File(projectRoot, "pacvue-commerce-deploy-extension/scripts/deploy-to-test.js"),
            File(projectRoot.parentFile ?: projectRoot, "pacvue-commerce-deploy-extension/scripts/deploy-to-test.js"),
        )
        return externalCandidates.firstOrNull { it.exists() } ?: error("Bundled deploy-to-test.js was not found.")
    }

    private fun parseJson(stdout: String): JsonObject? {
        val trimmed = stdout.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
    }

    private fun parseWorkflowRun(parsed: JsonObject?): WorkflowRunStatus? {
        val dispatch = parsed?.get("dispatch").asJsonObjectOrNull() ?: return null
        val run = dispatch.get("run").asJsonObjectOrNull() ?: return null
        return gson.fromJson(run, WorkflowRunStatus::class.java)
    }

    private fun runProcess(command: String, args: List<String>, cwd: File): ProcessResult {
        val executable = when (command) {
            "git" -> ExecutableResolver.resolveGitPath(project, cwd)
                ?: ExecutableResolver.resolveExecutableCommand(command)
            "gh" -> ExecutableResolver.resolveGhPath(cwd)
                ?: ExecutableResolver.resolveExecutableCommand(command)
            else -> ExecutableResolver.resolveExecutableCommand(command)
        }

        val processBuilder = ProcessBuilder(listOf(executable, *args.toTypedArray()))
            .directory(cwd)
        ExecutableResolver.applyChildProcessEnvironment(processBuilder, project, cwd)

        val process = processBuilder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val status = process.waitFor()
        return ProcessResult(status, stdout, stderr)
    }

    companion object {
        fun parseGithubRepoId(remoteUrl: String): String? {
            val normalized = remoteUrl.trim().removeSuffix(".git")
            if (normalized.isBlank()) return null

            val httpsMatch = Regex("""^https?://github\.com/([^/]+)/([^/]+)$""").matchEntire(normalized)
            if (httpsMatch != null) {
                return "${httpsMatch.groupValues[1]}/${httpsMatch.groupValues[2]}"
            }

            val sshMatch = Regex("""^git@github\.com:([^/]+)/([^/]+)$""").matchEntire(normalized)
            if (sshMatch != null) {
                return "${sshMatch.groupValues[1]}/${sshMatch.groupValues[2]}"
            }

            val sshUrlMatch = Regex("""^ssh://git@github\.com/([^/]+)/([^/]+)$""").matchEntire(normalized)
            if (sshUrlMatch != null) {
                return "${sshUrlMatch.groupValues[1]}/${sshUrlMatch.groupValues[2]}"
            }

            return null
        }
    }
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
    return this?.takeIf { it.isJsonObject }?.asJsonObject
}

data class ProcessResult(
    val status: Int,
    val stdout: String,
    val stderr: String,
)
