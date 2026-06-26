package com.pacvue.deploy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DeployServiceTest {
    @Test
    fun `parses https github remote as repo id`() {
        assertEquals(
            "lizhenqiang-pacvue/pacvue-commerce-deploy-extension",
            DeployService.parseGithubRepoId("https://github.com/lizhenqiang-pacvue/pacvue-commerce-deploy-extension.git"),
        )
    }

    @Test
    fun `parses scp-like ssh github remote as repo id`() {
        assertEquals(
            "lizhenqiang-pacvue/pacvue-commerce-deploy-extension",
            DeployService.parseGithubRepoId("git@github.com:lizhenqiang-pacvue/pacvue-commerce-deploy-extension.git"),
        )
    }

    @Test
    fun `parses ssh url github remote as repo id`() {
        assertEquals(
            "lizhenqiang-pacvue/pacvue-commerce-deploy-extension",
            DeployService.parseGithubRepoId("ssh://git@github.com/lizhenqiang-pacvue/pacvue-commerce-deploy-extension.git"),
        )
    }

    @Test
    fun `returns null for non github remotes`() {
        assertNull(DeployService.parseGithubRepoId("https://gitlab.example.com/team/repo.git"))
    }

    @Test
    fun `parses node major version`() {
        assertEquals(20, DeployService.parseNodeMajorVersion("v20.11.1"))
        assertEquals(16, DeployService.parseNodeMajorVersion("16.20.2"))
    }

    @Test
    fun `returns null for unparsable node version`() {
        assertNull(DeployService.parseNodeMajorVersion("node version unknown"))
    }

    @Test
    fun `diagnoses script parse failures`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(parsed = null, errorMessage = "Unexpected output from deploy script."),
        )

        assertEquals("script_parse_failed", diagnosis.category)
        assertEquals("plugin_code", diagnosis.triageRoute)
    }

    @Test
    fun `diagnoses workflow not found failures`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(
                parsed = json("""{"reason":"Workflow \"missing.yml\" was not found among workflow_dispatch workflows."}"""),
                errorMessage = "Workflow \"missing.yml\" was not found among workflow_dispatch workflows.",
            ),
        )

        assertEquals("workflow_not_found", diagnosis.category)
        assertEquals("project_config", diagnosis.triageRoute)
    }

    @Test
    fun `diagnoses workflow not found failures from unstructured script output`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(
                parsed = null,
                errorMessage = "Workflow \"missing.yml\" was not found among workflow_dispatch workflows.",
            ),
        )

        assertEquals("workflow_not_found", diagnosis.category)
        assertEquals("project_config", diagnosis.triageRoute)
    }

    @Test
    fun `diagnoses invalid input failures`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(
                parsed = json("""{"missingRequiredInputs":["ProjectName"]}"""),
                errorMessage = "Missing required inputs: ProjectName",
            ),
        )

        assertEquals("invalid_inputs", diagnosis.category)
        assertEquals("deploy_input", diagnosis.triageRoute)
    }

    @Test
    fun `diagnoses github auth failures`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(
                parsed = json("""{"reason":"gh auth status failed with HTTP 401"}"""),
                errorMessage = "gh auth status failed with HTTP 401",
            ),
        )

        assertEquals("github_auth_failed", diagnosis.category)
        assertEquals("github_auth", diagnosis.triageRoute)
    }

    @Test
    fun `diagnoses github cli unavailable failures`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(
                parsed = json("""{"reason":"未检测到 GitHub CLI（gh），无法触发 GitHub Actions workflow。"}"""),
                errorMessage = "未检测到 GitHub CLI（gh），无法触发 GitHub Actions workflow。安装后执行: gh auth login",
            ),
        )

        assertEquals("github_cli_unavailable", diagnosis.category)
        assertEquals("github_cli", diagnosis.triageRoute)
    }

    @Test
    fun `diagnoses workflow run failures`() {
        val diagnosis = DeployService.diagnoseDeployFailure(
            issueRequest(
                failureType = "run",
                run = WorkflowRunStatus(databaseId = 123, status = "completed", conclusion = "failure"),
                jobsSummary = listOf(WorkflowRunJobSummary(name = "build", conclusion = "failure")),
            ),
        )

        assertEquals("workflow_failed", diagnosis.category)
        assertEquals("workflow_runtime", diagnosis.triageRoute)
    }

    @Test
    fun `builds stable deploy failure fingerprint for equivalent failures`() {
        val first = issueRequest(
            errorMessage = "Workflow run #123 failed. See https://github.com/owner/repo/actions/runs/123456789.",
        )
        val second = issueRequest(
            errorMessage = "Workflow run #999 failed. See https://github.com/owner/repo/actions/runs/987654321.",
        )

        assertEquals(
            DeployService.buildDeployFailureFingerprint(first),
            DeployService.buildDeployFailureFingerprint(second),
        )
    }

    @Test
    fun `builds different deploy failure fingerprint for different project name`() {
        val first = issueRequest(inputs = mapOf("ProjectName" to "commerce-a"))
        val second = issueRequest(inputs = mapOf("ProjectName" to "commerce-b"))

        val firstFingerprint = DeployService.buildDeployFailureFingerprint(first)
        val secondFingerprint = DeployService.buildDeployFailureFingerprint(second)

        assertNotEquals(firstFingerprint, secondFingerprint)
    }

    private fun issueRequest(
        failureType: String = "trigger",
        parsed: com.google.gson.JsonObject? = json("""{"ok":false}"""),
        errorMessage: String? = "Deploy failed.",
        run: WorkflowRunStatus? = null,
        jobsSummary: List<WorkflowRunJobSummary> = emptyList(),
        inputs: Map<String, String> = mapOf("ProjectName" to "demo"),
    ): DeployFailureIssueRequest {
        return DeployFailureIssueRequest(
            failureType = failureType,
            commerceRepo = "owner/repo",
            targetBranch = "test/demo",
            workflow = ".github/workflows/deploy.yml",
            workflowName = "测试环境发版",
            inputs = inputs,
            command = "node deploy-to-test.js",
            errorMessage = errorMessage,
            run = run,
            parsed = parsed,
            jobsSummary = jobsSummary,
        )
    }

    private fun json(text: String): com.google.gson.JsonObject {
        return JsonParser.parseString(text).asJsonObject
    }
}
