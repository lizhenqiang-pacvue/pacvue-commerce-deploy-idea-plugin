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

data class WorkflowRunJobSummary(
    val name: String = "",
    val conclusion: String? = null,
    val failedSteps: List<String> = emptyList(),
)

data class DeployFailureIssueRequest(
    val failureType: String,
    val commerceRepo: String,
    val targetBranch: String,
    val workflow: String,
    val workflowName: String,
    val inputs: Map<String, String>,
    val command: String?,
    val errorMessage: String?,
    val run: WorkflowRunStatus?,
    val parsed: JsonObject?,
    val jobsSummary: List<WorkflowRunJobSummary> = emptyList(),
)

data class DeployIssueResult(
    val ok: Boolean,
    val issueUrl: String? = null,
    val error: String? = null,
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

    fun getRunJobsSummary(runId: Long): List<WorkflowRunJobSummary> {
        val result = runProcess("gh", listOf("run", "view", runId.toString(), "--json", "jobs"), projectRoot)
        if (result.status != 0) return emptyList()

        return runCatching {
            val jobs = JsonParser.parseString(result.stdout)
                .asJsonObjectOrNull()
                ?.get("jobs")
                .asJsonArrayOrNull()
                ?: return@runCatching emptyList()

            jobs.mapNotNull { jobElement ->
                val job = jobElement.asJsonObjectOrNull() ?: return@mapNotNull null
                val failedSteps = job.get("steps")
                    .asJsonArrayOrNull()
                    .orEmpty()
                    .mapNotNull stepMap@{ stepElement ->
                        val step = stepElement.asJsonObjectOrNull() ?: return@stepMap null
                        val conclusion = step.get("conclusion").asNonBlankStringOrNull()
                        step.get("name").asNonBlankStringOrNull().takeIf { conclusion == "failure" }
                    }
                val conclusion = job.get("conclusion").asNonBlankStringOrNull()
                WorkflowRunJobSummary(
                    name = job.get("name").asNonBlankStringOrNull() ?: "unknown",
                    conclusion = conclusion,
                    failedSteps = failedSteps,
                ).takeIf { it.conclusion == "failure" || it.failedSteps.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    fun createDeployFailureIssue(request: DeployFailureIssueRequest): DeployIssueResult {
        val issueRepoSlug = System.getenv("PACVUE_DEPLOY_ISSUE_REPO")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ISSUE_REPO
        val title = buildDeployFailureIssueTitle(request)
        val body = trimIssueBody(buildDeployFailureIssueBody(request))
        val bodyFile = Files.createTempFile("pacvue-deploy-issue-", ".md").toFile()

        return try {
            bodyFile.writeText(body)
            val result = runProcess(
                "gh",
                listOf("issue", "create", "--repo", issueRepoSlug, "--title", title, "--body-file", bodyFile.absolutePath),
                projectRoot,
            )
            if (result.status == 0) {
                DeployIssueResult(
                    ok = true,
                    issueUrl = result.stdout
                        .lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") },
                )
            } else {
                DeployIssueResult(ok = false, error = previewCommandOutput(result.stdout, result.stderr, "Failed to create deploy failure issue."))
            }
        } catch (error: Throwable) {
            DeployIssueResult(ok = false, error = error.message ?: "Failed to create deploy failure issue.")
        } finally {
            bodyFile.delete()
        }
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
        private const val DEFAULT_ISSUE_REPO = "lizhenqiang-pacvue/pacvue-commerce-deploy-idea-plugin"
        private const val ISSUE_BODY_MAX_CHARS = 60000
        private const val GITHUB_FILE_MAX_CHARS = 6000
        private const val GITHUB_SNAPSHOT_MAX_FILES = 12

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

    private fun buildDeployFailureIssueTitle(request: DeployFailureIssueRequest): String {
        val workflowName = request.workflowName.ifBlank { request.workflow.ifBlank { "workflow" } }
        return if (request.failureType == "trigger") {
            "[auto-triage] Deploy trigger failed: $workflowName @ ${request.targetBranch}"
        } else {
            val runSuffix = request.run?.databaseId?.let { " (run #$it)" }.orEmpty()
            "[auto-triage] Deploy failed: $workflowName @ ${request.targetBranch}$runSuffix"
        }
    }

    private fun buildDeployFailureIssueBody(request: DeployFailureIssueRequest): String {
        val githubSnapshot = readGithubSnapshot()
        val runUrl = request.run?.url.orEmpty()
        val conclusion = request.run?.conclusion.orEmpty()
        val errorMessage = request.errorMessage.orEmpty()
        val failedJobsBlock = if (request.jobsSummary.isNotEmpty()) {
            request.jobsSummary.joinToString("\n") { job ->
                val failedSteps = job.failedSteps.takeIf { it.isNotEmpty() }?.joinToString(", ")
                "- **${job.name}** (${job.conclusion ?: "unknown"})${failedSteps?.let { ": $it" }.orEmpty()}"
            }
        } else {
            "_No failed job details were available from GitHub Actions CLI._"
        }
        val payload = mapOf(
            "type" to if (request.failureType == "trigger") "deploy_trigger_failure" else "deploy_run_failure",
            "commerceRepo" to request.commerceRepo,
            "workflow" to request.workflow,
            "workflowName" to request.workflowName,
            "targetBranch" to request.targetBranch,
            "inputs" to request.inputs,
            "runId" to request.run?.databaseId,
            "runUrl" to runUrl.ifBlank { null },
            "conclusion" to conclusion.ifBlank { null },
            "command" to request.command,
            "errorMessage" to errorMessage.ifBlank { null },
            "reporter" to "pacvue-commerce-deploy-idea-plugin",
            "reportedAt" to java.time.Instant.now().toString(),
            "githubFiles" to githubSnapshot.files.map {
                mapOf(
                    "path" to it.path,
                    "size" to it.size,
                    "truncated" to it.truncated,
                    "skipped" to it.skipped,
                )
            },
        )

        return listOf(
            "## Summary",
            if (request.failureType == "trigger") {
                "Pacvue Deploy failed to trigger the GitHub Actions workflow."
            } else {
                "Pacvue Deploy workflow run completed with a non-success result."
            },
            "",
            "## Context",
            "",
            "| Field | Value |",
            "| --- | --- |",
            "| Commerce repo | `${escapeTableValue(request.commerceRepo)}` |",
            "| Workflow | `${escapeTableValue(request.workflowName.ifBlank { request.workflow })}` |",
            "| Workflow file | `${escapeTableValue(request.workflow.ifBlank { "n/a" })}` |",
            "| Target branch | `${escapeTableValue(request.targetBranch.ifBlank { "n/a" })}` |",
            if (runUrl.isNotBlank()) "| Run URL | $runUrl |" else "| Run URL | n/a |",
            if (conclusion.isNotBlank()) "| Conclusion | `${escapeTableValue(conclusion)}` |" else null,
            "",
            "## Inputs",
            "",
            formatIssueInputs(request.inputs),
            "",
            "## Error",
            "",
            "```text",
            errorMessage.ifBlank { "(no error message captured)" },
            "```",
            "",
            "## Failed jobs",
            "",
            failedJobsBlock,
            "",
            "## Project `.github` configuration",
            "",
            buildGithubFilesIssueSection(githubSnapshot),
            "",
            request.command?.takeIf { it.isNotBlank() }?.let {
                listOf("## Command", "", "```bash", it, "```", "").joinToString("\n")
            },
            "## Payload",
            "",
            "```json",
            gson.toJson(payload),
            "```",
            "",
            "_Auto-created by Pacvue Commerce Deploy IDEA plugin. Label or assign for agent triage._",
        )
            .filterNotNull()
            .joinToString("\n")
    }

    private fun readGithubSnapshot(): GithubSnapshot {
        val githubDir = File(projectRoot, ".github")
        if (!githubDir.exists() || !githubDir.isDirectory) {
            return GithubSnapshot(missing = true)
        }

        val files = githubDir
            .walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(projectRoot).path }
            .toList()
        val included = files.take(GITHUB_SNAPSHOT_MAX_FILES).map { file ->
            val relativePath = file.relativeTo(projectRoot).path
            val size = file.length()
            val content = runCatching { file.readText() }.getOrNull()
            when {
                content == null -> GithubSnapshotFile(path = relativePath, size = size, skipped = "unreadable")
                content.length > GITHUB_FILE_MAX_CHARS -> GithubSnapshotFile(
                    path = relativePath,
                    size = size,
                    content = content.take(GITHUB_FILE_MAX_CHARS),
                    truncated = true,
                )
                else -> GithubSnapshotFile(path = relativePath, size = size, content = content)
            }
        }

        return GithubSnapshot(files = included, totalDiscovered = files.size)
    }

    private fun buildGithubFilesIssueSection(snapshot: GithubSnapshot): String {
        if (snapshot.missing) return "_No `.github` directory found in the current workspace._"
        if (snapshot.files.isEmpty()) return "_No readable files found under `.github`._"

        val sections = snapshot.files.joinToString("\n\n") { file ->
            if (file.content == null) {
                "### `${file.path}`\n\n_Skipped: ${file.skipped ?: "unavailable"} (${file.size} bytes)._"
            } else {
                val truncatedNote = if (file.truncated) "\n\n_Content truncated for issue size limits._" else ""
                "### `${file.path}`$truncatedNote\n\n```${githubFileFenceLanguage(file.path)}\n${file.content}\n```"
            }
        }
        val footer = if (snapshot.totalDiscovered > snapshot.files.size) {
            "\n\n_Showing ${snapshot.files.size} of ${snapshot.totalDiscovered} files under `.github`._"
        } else {
            ""
        }
        return sections + footer
    }

    private fun githubFileFenceLanguage(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "yml", "yaml" -> "yaml"
            "json" -> "json"
            "sh" -> "bash"
            "md" -> "markdown"
            else -> "text"
        }
    }

    private fun formatIssueInputs(inputs: Map<String, String>): String {
        if (inputs.isEmpty()) return "_No inputs captured._"
        return inputs.entries.joinToString("\n") { (key, value) -> "- `$key`: `${value.ifBlank { "(blank)" }}`" }
    }

    private fun trimIssueBody(body: String): String {
        if (body.length <= ISSUE_BODY_MAX_CHARS) return body
        return body.take(ISSUE_BODY_MAX_CHARS - 120) + "\n\n...(issue body truncated for GitHub size limit)..."
    }

    private fun previewCommandOutput(stdout: String, stderr: String, fallback: String): String {
        return listOf(stderr, stdout)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(2000)
            .ifBlank { fallback }
    }

    private fun escapeTableValue(value: String): String {
        return value.replace("|", "\\|")
    }

    private data class GithubSnapshot(
        val missing: Boolean = false,
        val files: List<GithubSnapshotFile> = emptyList(),
        val totalDiscovered: Int = 0,
    )

    private data class GithubSnapshotFile(
        val path: String,
        val size: Long,
        val content: String? = null,
        val truncated: Boolean = false,
        val skipped: String? = null,
    )
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
    return this?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonElement?.asJsonArrayOrNull(): List<JsonElement>? {
    return this?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
}

private fun JsonElement?.asNonBlankStringOrNull(): String? {
    return this
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.takeIf { it.isNotBlank() }
}

data class ProcessResult(
    val status: Int,
    val stdout: String,
    val stderr: String,
)
