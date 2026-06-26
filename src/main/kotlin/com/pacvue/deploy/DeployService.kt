package com.pacvue.deploy

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

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
    val warning: String? = null,
    val deduplicated: Boolean = false,
)

data class DeployFailureDiagnosis(
    val category: String,
    val summary: String,
    val triageRoute: String,
    val recommendedAction: String,
    val labels: List<String> = emptyList(),
)

enum class EnvironmentCheckStatus {
    OK,
    WARNING,
    ERROR,
}

data class EnvironmentCheckResult(
    val label: String,
    val status: EnvironmentCheckStatus,
    val message: String,
)

data class EnvironmentCheckReport(
    val checks: List<EnvironmentCheckResult> = emptyList(),
) {
    val hasErrors: Boolean
        get() = checks.any { it.status == EnvironmentCheckStatus.ERROR }
}

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

    fun runEnvironmentChecks(): EnvironmentCheckReport {
        val checks = mutableListOf<EnvironmentCheckResult>()
        val root = projectRoot

        checks.add(
            if (root.exists() && root.isDirectory) {
                EnvironmentCheckResult("Project", EnvironmentCheckStatus.OK, root.absolutePath)
            } else {
                EnvironmentCheckResult("Project", EnvironmentCheckStatus.ERROR, "Project root does not exist: ${root.absolutePath}")
            },
        )

        val gitVersion = runProcessForCheck("git", listOf("--version"), root)
        if (gitVersion?.status == 0) {
            checks.add(EnvironmentCheckResult("Git", EnvironmentCheckStatus.OK, gitVersion.stdout.trim().ifBlank { "git is available." }))

            val insideWorkTree = runProcessForCheck("git", listOf("rev-parse", "--is-inside-work-tree"), root)
            checks.add(
                if (insideWorkTree?.status == 0 && insideWorkTree.stdout.trim() == "true") {
                    EnvironmentCheckResult("Git repository", EnvironmentCheckStatus.OK, "Current project is a Git working tree.")
                } else {
                    EnvironmentCheckResult("Git repository", EnvironmentCheckStatus.ERROR, "Current project is not a Git working tree.")
                },
            )

            val remote = runProcessForCheck("git", listOf("config", "--get", "remote.origin.url"), root)
            checks.add(
                if (remote?.status == 0 && remote.stdout.trim().isNotBlank()) {
                    EnvironmentCheckResult("Git origin", EnvironmentCheckStatus.OK, remote.stdout.trim())
                } else {
                    EnvironmentCheckResult("Git origin", EnvironmentCheckStatus.WARNING, "remote.origin.url is not configured.")
                },
            )
        } else {
            checks.add(
                EnvironmentCheckResult(
                    "Git",
                    EnvironmentCheckStatus.ERROR,
                    "git is required. Install Git or expose it through PATH / ${ExecutableResolver.GIT_PATH_ENV}.",
                ),
            )
        }

        val workflowsDir = File(root, ".github/workflows")
        val workflowFiles = workflowsDir.listFiles { file -> isWorkflowFile(file) }.orEmpty()
        checks.add(
            when {
                !workflowsDir.exists() || !workflowsDir.isDirectory -> EnvironmentCheckResult(
                    "GitHub workflows",
                    EnvironmentCheckStatus.ERROR,
                    "Missing .github/workflows directory.",
                )

                workflowFiles.isEmpty() -> EnvironmentCheckResult(
                    "GitHub workflows",
                    EnvironmentCheckStatus.ERROR,
                    "No .yml or .yaml workflow files were found under .github/workflows.",
                )

                else -> EnvironmentCheckResult(
                    "GitHub workflows",
                    EnvironmentCheckStatus.OK,
                    "Found ${workflowFiles.size} workflow file(s).",
                )
            },
        )

        val nodeVersion = runProcessForCheck("node", listOf("--version"), root)
        if (nodeVersion?.status == 0) {
            val versionText = nodeVersion.stdout.trim().lineSequence().firstOrNull().orEmpty()
            val majorVersion = parseNodeMajorVersion(versionText)
            checks.add(
                when {
                    majorVersion == null -> EnvironmentCheckResult("Node.js", EnvironmentCheckStatus.WARNING, "Node.js is available, but version could not be parsed: $versionText")
                    majorVersion < 16 -> EnvironmentCheckResult("Node.js", EnvironmentCheckStatus.ERROR, "Node.js 16+ is required. Current version: $versionText")
                    else -> EnvironmentCheckResult("Node.js", EnvironmentCheckStatus.OK, versionText)
                },
            )
        } else {
            checks.add(EnvironmentCheckResult("Node.js", EnvironmentCheckStatus.ERROR, "Node.js 16+ is required to run the bundled deploy script."))
        }

        val ghVersion = runProcessForCheck("gh", listOf("--version"), root)
        if (ghVersion?.status == 0) {
            checks.add(EnvironmentCheckResult("GitHub CLI", EnvironmentCheckStatus.OK, ghVersion.stdout.lineSequence().firstOrNull().orEmpty().ifBlank { "gh is available." }))

            val authStatus = runProcessForCheck("gh", listOf("auth", "status"), root)
            checks.add(
                if (authStatus?.status == 0) {
                    EnvironmentCheckResult("GitHub auth", EnvironmentCheckStatus.OK, "gh auth status succeeded.")
                } else {
                    EnvironmentCheckResult(
                        "GitHub auth",
                        EnvironmentCheckStatus.ERROR,
                        previewCommandOutput(authStatus?.stdout.orEmpty(), authStatus?.stderr.orEmpty(), "Run gh auth login before deploying."),
                    )
                },
            )
        } else {
            checks.add(
                EnvironmentCheckResult(
                    "GitHub CLI",
                    EnvironmentCheckStatus.ERROR,
                    "gh is required to trigger, poll, open, and cancel GitHub Actions runs. Install gh and run gh auth login.",
                ),
            )
        }

        return EnvironmentCheckReport(checks)
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
        val diagnosis = diagnoseDeployFailure(request)
        val fingerprint = buildDeployFailureFingerprint(request, diagnosis)
        val duplicateIssue = findOpenDuplicateIssue(issueRepoSlug, fingerprint)
        if (duplicateIssue != null) {
            return appendDuplicateIssueComment(issueRepoSlug, duplicateIssue, request, diagnosis, fingerprint)
        }

        val title = buildDeployFailureIssueTitle(request, diagnosis)
        val body = trimIssueBody(buildDeployFailureIssueBody(request, diagnosis, fingerprint))
        val bodyFile = Files.createTempFile("pacvue-deploy-issue-", ".md").toFile()

        return try {
            bodyFile.writeText(body)
            val result = runProcess(
                "gh",
                listOf("issue", "create", "--repo", issueRepoSlug, "--title", title, "--body-file", bodyFile.absolutePath),
                projectRoot,
            )
            if (result.status == 0) {
                val issueUrl = result.stdout
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                val labelWarning = addIssueLabels(issueRepoSlug, issueUrl, diagnosis.labels)
                DeployIssueResult(
                    ok = true,
                    issueUrl = issueUrl,
                    warning = labelWarning,
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

    private fun findOpenDuplicateIssue(issueRepoSlug: String, fingerprint: String): GithubIssueSummary? {
        val result = runCatching {
            runProcess(
                "gh",
                listOf(
                    "issue",
                    "list",
                    "--repo",
                    issueRepoSlug,
                    "--state",
                    "open",
                    "--search",
                    fingerprint,
                    "--json",
                    "number,title,url",
                    "--limit",
                    "5",
                ),
                projectRoot,
            )
        }.getOrNull() ?: return null

        if (result.status != 0 || result.stdout.isBlank()) return null

        return runCatching {
            gson.fromJson(result.stdout, Array<GithubIssueSummary>::class.java)
                .firstOrNull { it.url.isNotBlank() || it.number != null }
        }.getOrNull()
    }

    private fun appendDuplicateIssueComment(
        issueRepoSlug: String,
        issue: GithubIssueSummary,
        request: DeployFailureIssueRequest,
        diagnosis: DeployFailureDiagnosis,
        fingerprint: String,
    ): DeployIssueResult {
        val bodyFile = Files.createTempFile("pacvue-deploy-duplicate-", ".md").toFile()
        return try {
            bodyFile.writeText(trimIssueBody(buildDeployFailureIssueComment(request, diagnosis, fingerprint)))
            val issueSelector = issue.url.takeIf { it.isNotBlank() } ?: issue.number?.toString().orEmpty()
            val result = runProcess(
                "gh",
                listOf("issue", "comment", issueSelector, "--repo", issueRepoSlug, "--body-file", bodyFile.absolutePath),
                projectRoot,
            )
            if (result.status == 0) {
                DeployIssueResult(ok = true, issueUrl = issue.url, deduplicated = true)
            } else {
                DeployIssueResult(
                    ok = false,
                    issueUrl = issue.url,
                    error = previewCommandOutput(result.stdout, result.stderr, "Duplicate issue found, but failed to append comment."),
                    deduplicated = true,
                )
            }
        } catch (error: Throwable) {
            DeployIssueResult(
                ok = false,
                issueUrl = issue.url,
                error = error.message ?: "Duplicate issue found, but failed to append comment.",
                deduplicated = true,
            )
        } finally {
            bodyFile.delete()
        }
    }

    private fun addIssueLabels(issueRepoSlug: String, issueUrl: String?, labels: List<String>): String? {
        if (issueUrl.isNullOrBlank() || labels.isEmpty()) return null

        val result = runCatching {
            runProcess(
                "gh",
                listOf("issue", "edit", issueUrl, "--repo", issueRepoSlug, "--add-label", labels.joinToString(",")),
                projectRoot,
            )
        }.getOrNull() ?: return "Issue was created, but labels could not be added."

        return if (result.status == 0) {
            null
        } else {
            previewCommandOutput(result.stdout, result.stderr, "Issue was created, but labels could not be added.")
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

    private fun runProcessForCheck(command: String, args: List<String>, cwd: File): ProcessResult? {
        return runCatching { runProcess(command, args, cwd) }.getOrNull()
    }

    private fun isWorkflowFile(file: File): Boolean {
        return file.isFile &&
            (file.extension.equals("yml", ignoreCase = true) || file.extension.equals("yaml", ignoreCase = true))
    }

    companion object {
        private const val DEFAULT_ISSUE_REPO = "lizhenqiang-pacvue/pacvue-commerce-deploy-idea-plugin"
        private const val ISSUE_BODY_MAX_CHARS = 60000
        private const val GITHUB_FILE_MAX_CHARS = 6000
        private const val GITHUB_SNAPSHOT_MAX_FILES = 12

        fun parseNodeMajorVersion(versionOutput: String): Int? {
            return Regex("""v?(\d+)""")
                .find(versionOutput.trim())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }

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

        fun diagnoseDeployFailure(request: DeployFailureIssueRequest): DeployFailureDiagnosis {
            if (request.failureType == "run") {
                val conclusion = request.run?.conclusion?.takeIf { it.isNotBlank() } ?: "unknown"
                val failedJobs = request.jobsSummary.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { it.name }
                    .orEmpty()
                return failureDiagnosis(
                    category = "workflow_failed",
                    summary = if (failedJobs.isBlank()) {
                        "GitHub Actions workflow completed with conclusion: $conclusion."
                    } else {
                        "GitHub Actions workflow completed with conclusion: $conclusion. Failed jobs: $failedJobs."
                    },
                    triageRoute = "workflow_runtime",
                    recommendedAction = "Open the run, inspect failed jobs and steps, then fix the workflow, build command, or project runtime error.",
                )
            }

            val parsed = request.parsed
            val errorText = listOf(
                request.errorMessage.orEmpty(),
                parsed?.get("reason").asNonBlankStringOrNull().orEmpty(),
                parsed?.get("commandPreview").asNonBlankStringOrNull().orEmpty(),
                parsed?.get("dispatch")
                    .asJsonObjectOrNull()
                    ?.get("stderr")
                    .asNonBlankStringOrNull()
                    .orEmpty(),
                parsed?.get("dispatch")
                    .asJsonObjectOrNull()
                    ?.get("stdout")
                    .asNonBlankStringOrNull()
                    .orEmpty(),
            )
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val normalized = errorText.lowercase()

            val missingRequiredInputs = parsed?.get("missingRequiredInputs")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.takeIf { it.size() > 0 }
            if (missingRequiredInputs != null) {
                return failureDiagnosis(
                    category = "invalid_inputs",
                    summary = "Deploy inputs are missing required values: $missingRequiredInputs.",
                    triageRoute = "deploy_input",
                    recommendedAction = "Fill required workflow inputs or update workflow defaults before running deploy again.",
                )
            }

            if (
                normalized.contains("workflow") &&
                (
                    normalized.contains("was not found") ||
                        normalized.contains("no workflow") ||
                        normalized.contains("multiple matching workflows") ||
                        normalized.contains("does not use workflow_dispatch")
                    )
            ) {
                return failureDiagnosis(
                    category = "workflow_not_found",
                    summary = "The selected workflow is missing, ambiguous, or does not support workflow_dispatch.",
                    triageRoute = "project_config",
                    recommendedAction = "Check .github/workflows in the Commerce project and ensure the selected workflow exists and supports workflow_dispatch.",
                )
            }

            if (
                normalized.contains("remote branch") ||
                normalized.contains("not found on origin") ||
                normalized.contains("git is required") ||
                normalized.contains("git 命令") ||
                normalized.contains("missing .github") ||
                normalized.contains("no .yml") ||
                normalized.contains("no .yaml")
            ) {
                return failureDiagnosis(
                    category = "invalid_project_config",
                    summary = "The current project Git or GitHub workflow configuration does not meet deploy requirements.",
                    triageRoute = "project_config",
                    recommendedAction = "Ask the project owner to fix Git remote, target branch, or .github/workflows configuration.",
                )
            }

            if (
                normalized.contains("未检测到 github cli") ||
                normalized.contains("github cli") && (normalized.contains("install") || normalized.contains("安装"))
            ) {
                return failureDiagnosis(
                    category = "github_cli_unavailable",
                    summary = "GitHub CLI is not installed or not visible to the IDE process.",
                    triageRoute = "github_cli",
                    recommendedAction = "Install GitHub CLI, run gh auth login, then fully restart the IDE so the plugin can read the updated PATH.",
                )
            }

            if (
                normalized.contains("github cli") ||
                normalized.contains("gh auth") ||
                normalized.contains("gh:") ||
                normalized.contains("authentication") ||
                normalized.contains("not authenticated") ||
                normalized.contains("permission") ||
                normalized.contains("forbidden") ||
                normalized.contains("resource not accessible") ||
                normalized.contains("http 401") ||
                normalized.contains("http 403")
            ) {
                return failureDiagnosis(
                    category = "github_auth_failed",
                    summary = "GitHub CLI authentication or permissions prevented the deploy operation.",
                    triageRoute = "github_auth",
                    recommendedAction = "Run gh auth status / gh auth login and confirm repo + workflow permissions and organization SSO authorization.",
                )
            }

            if (
                normalized.contains("no github actions run was found") ||
                normalized.contains("workflow run returned successfully") ||
                normalized.contains("verified\":false") ||
                normalized.contains("dispatch error")
            ) {
                return failureDiagnosis(
                    category = "dispatch_failed",
                    summary = "The workflow dispatch did not produce a verifiable GitHub Actions run.",
                    triageRoute = "github_dispatch",
                    recommendedAction = "Check GitHub Actions dispatch permissions, workflow ref, branch filters, and whether the run was delayed or rejected.",
                )
            }

            if (parsed == null) {
                return failureDiagnosis(
                    category = "script_parse_failed",
                    summary = "Deploy script output could not be parsed as structured JSON.",
                    triageRoute = "plugin_code",
                    recommendedAction = "Check the IDEA plugin and deploy-to-test.js output parsing path; this is likely a plugin/script integration issue.",
                )
            }

            return failureDiagnosis(
                category = "unknown",
                summary = "Deploy failed, but the plugin could not classify the failure with current rules.",
                triageRoute = "manual_triage",
                recommendedAction = "Review the captured error, command, workflow file, and project .github snapshot.",
            )
        }

        private fun failureDiagnosis(
            category: String,
            summary: String,
            triageRoute: String,
            recommendedAction: String,
        ): DeployFailureDiagnosis {
            return DeployFailureDiagnosis(
                category = category,
                summary = summary,
                triageRoute = triageRoute,
                recommendedAction = recommendedAction,
                labels = listOf("auto-triage", "deploy-failure", category),
            )
        }

        fun buildDeployFailureFingerprint(
            request: DeployFailureIssueRequest,
            diagnosis: DeployFailureDiagnosis = diagnoseDeployFailure(request),
        ): String {
            val rawFingerprint = listOf(
                "pacvue-deploy-failure",
                diagnosis.category,
                request.failureType,
                request.commerceRepo,
                request.workflow,
                request.targetBranch,
                getInputValue(request.inputs, "ProjectName"),
                normalizeFingerprintText(buildFingerprintErrorSummary(request, diagnosis)),
            )
                .joinToString("|")

            return MessageDigest
                .getInstance("SHA-256")
                .digest(rawFingerprint.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun getInputValue(inputs: Map<String, String>, key: String): String {
            return inputs.entries
                .firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value
                .orEmpty()
        }

        private fun buildFingerprintErrorSummary(
            request: DeployFailureIssueRequest,
            diagnosis: DeployFailureDiagnosis,
        ): String {
            val failedJobs = request.jobsSummary.joinToString("\n") { job ->
                listOf(
                    job.name,
                    job.conclusion.orEmpty(),
                    job.failedSteps.joinToString(","),
                )
                    .filter { it.isNotBlank() }
                    .joinToString(":")
            }
            val parsed = request.parsed
            return listOf(
                diagnosis.summary,
                request.errorMessage.orEmpty(),
                failedJobs,
                parsed?.get("reason").asNonBlankStringOrNull().orEmpty(),
                parsed?.get("dispatch")
                    .asJsonObjectOrNull()
                    ?.get("stderr")
                    .asNonBlankStringOrNull()
                    .orEmpty(),
            )
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        private fun normalizeFingerprintText(text: String): String {
            return text
                .lowercase()
                .replace(Regex("""https?://\S+"""), "<url>")
                .replace(Regex("""run #?\d+"""), "run <id>")
                .replace(Regex("""\b\d{6,}\b"""), "<number>")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(600)
        }
    }

    private fun buildDeployFailureIssueTitle(request: DeployFailureIssueRequest, diagnosis: DeployFailureDiagnosis): String {
        val workflowName = request.workflowName.ifBlank { request.workflow.ifBlank { "workflow" } }
        return if (request.failureType == "trigger") {
            "[auto-triage][${diagnosis.category}] Deploy trigger failed: $workflowName @ ${request.targetBranch}"
        } else {
            val runSuffix = request.run?.databaseId?.let { " (run #$it)" }.orEmpty()
            "[auto-triage][${diagnosis.category}] Deploy failed: $workflowName @ ${request.targetBranch}$runSuffix"
        }
    }

    private fun buildDeployFailureIssueBody(
        request: DeployFailureIssueRequest,
        diagnosis: DeployFailureDiagnosis,
        fingerprint: String,
    ): String {
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
            "failureCategory" to diagnosis.category,
            "triageRoute" to diagnosis.triageRoute,
            "diagnosisSummary" to diagnosis.summary,
            "recommendedAction" to diagnosis.recommendedAction,
            "dedupeFingerprint" to fingerprint,
            "labels" to diagnosis.labels,
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
            "<!-- pacvue-deploy-fingerprint: $fingerprint -->",
            "",
            "## Summary",
            if (request.failureType == "trigger") {
                "Pacvue Deploy failed to trigger the GitHub Actions workflow."
            } else {
                "Pacvue Deploy workflow run completed with a non-success result."
            },
            "",
            "## Diagnosis",
            "",
            "| Field | Value |",
            "| --- | --- |",
            "| Failure category | `${escapeTableValue(diagnosis.category)}` |",
            "| Triage route | `${escapeTableValue(diagnosis.triageRoute)}` |",
            "| Summary | ${escapeTableValue(diagnosis.summary)} |",
            "| Recommended action | ${escapeTableValue(diagnosis.recommendedAction)} |",
            "| Dedupe fingerprint | `$fingerprint` |",
            "| Labels | `${escapeTableValue(diagnosis.labels.joinToString(", "))}` |",
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

    private fun buildDeployFailureIssueComment(
        request: DeployFailureIssueRequest,
        diagnosis: DeployFailureDiagnosis,
        fingerprint: String,
    ): String {
        val runUrl = request.run?.url.orEmpty()
        val conclusion = request.run?.conclusion.orEmpty()
        val failedJobsBlock = if (request.jobsSummary.isNotEmpty()) {
            request.jobsSummary.joinToString("\n") { job ->
                val failedSteps = job.failedSteps.takeIf { it.isNotEmpty() }?.joinToString(", ")
                "- **${job.name}** (${job.conclusion ?: "unknown"})${failedSteps?.let { ": $it" }.orEmpty()}"
            }
        } else {
            "_No failed job details were available from GitHub Actions CLI._"
        }

        return listOf(
            "<!-- pacvue-deploy-fingerprint: $fingerprint -->",
            "",
            "## Duplicate deploy failure occurrence",
            "",
            "A new failure matched this open issue by dedupe fingerprint.",
            "",
            "| Field | Value |",
            "| --- | --- |",
            "| Reported at | `${java.time.Instant.now()}` |",
            "| Failure category | `${escapeTableValue(diagnosis.category)}` |",
            "| Triage route | `${escapeTableValue(diagnosis.triageRoute)}` |",
            "| Workflow | `${escapeTableValue(request.workflowName.ifBlank { request.workflow })}` |",
            "| Workflow file | `${escapeTableValue(request.workflow.ifBlank { "n/a" })}` |",
            "| Target branch | `${escapeTableValue(request.targetBranch.ifBlank { "n/a" })}` |",
            if (runUrl.isNotBlank()) "| Run URL | $runUrl |" else "| Run URL | n/a |",
            if (conclusion.isNotBlank()) "| Conclusion | `${escapeTableValue(conclusion)}` |" else null,
            "| Dedupe fingerprint | `$fingerprint` |",
            "",
            "## Inputs",
            "",
            formatIssueInputs(request.inputs),
            "",
            "## Error",
            "",
            "```text",
            request.errorMessage.orEmpty().ifBlank { "(no error message captured)" },
            "```",
            "",
            "## Failed jobs",
            "",
            failedJobsBlock,
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

    private data class GithubIssueSummary(
        val number: Int? = null,
        val title: String = "",
        val url: String = "",
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
