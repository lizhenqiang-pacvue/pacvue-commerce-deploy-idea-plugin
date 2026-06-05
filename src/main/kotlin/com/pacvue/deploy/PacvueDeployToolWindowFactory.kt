package com.pacvue.deploy

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

class PacvueDeployToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PacvueDeployPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class PacvueDeployPanel(project: Project) : JPanel(BorderLayout()) {
    private val service = DeployService(project)
    private val branchCombo = createSearchableComboBox()
    private val workflowCombo = createSearchableWorkflowComboBox()
    private val inputsPanel = JPanel(GridBagLayout())
    private val outputArea = JTextArea("Loading deploy metadata...")
    private val workflowStatusLabel = JLabel("Workflow status: Idle")
    private val executionResultLabel = JLabel("Execution result: Not started")
    private val refreshButton = JButton("Refresh")
    private val runButton = JButton("Run")
    private val cancelButton = JButton("Cancel")
    private val inputFields = linkedMapOf<String, JComponent>()
    private var workflows = emptyList<WorkflowMetadata>()
    private var lastRunStatusText = ""
    @Volatile private var activeRunId: Long? = null
    @Volatile private var pollToken = 0

    init {
        border = JBUI.Borders.empty(12)

        outputArea.isEditable = false
        outputArea.lineWrap = true
        outputArea.wrapStyleWord = true
        outputArea.margin = JBUI.insets(8, 10)

        val formPanel = JPanel(GridBagLayout())
        addFormRow(formPanel, 0, "Target branch", branchCombo)
        addFormRow(formPanel, 1, "Workflow", workflowCombo)
        addFormRow(formPanel, 2, "Inputs", inputsPanel)
        addFormRow(formPanel, 3, "Workflow status", workflowStatusLabel)
        addFormRow(formPanel, 4, "Execution result", executionResultLabel)

        val actionsPanel = JPanel()
        actionsPanel.border = JBUI.Borders.empty(4, 0, 8, 0)
        actionsPanel.add(refreshButton)
        actionsPanel.add(runButton)
        actionsPanel.add(cancelButton)

        val topPanel = JPanel(BorderLayout(0, 8))
        topPanel.add(formPanel, BorderLayout.NORTH)
        topPanel.add(actionsPanel, BorderLayout.SOUTH)

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(outputArea), BorderLayout.CENTER)

        refreshButton.addActionListener { refreshState() }
        workflowCombo.addActionListener { renderInputs(getSelectedWorkflow()) }
        runButton.addActionListener { runDeploy() }
        cancelButton.addActionListener { cancelActiveRun() }

        setBusy(true)
        cancelButton.isEnabled = false
        refreshState()
    }

    private fun refreshState() {
        setBusy(true)
        outputArea.text = "Loading branches and workflows..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val currentBranch = service.getCurrentBranch()
                val branchOptions = service.getBranchOptions(currentBranch)
                val workflowMetadata = service.getWorkflowMetadata()
                Triple(currentBranch, branchOptions, workflowMetadata.workflows)
            }

            SwingUtilities.invokeLater {
                result
                    .onSuccess { (currentBranch, branchOptions, loadedWorkflows) ->
                        workflows = loadedWorkflows
                        setComboOptions(
                            branchCombo,
                            branchOptions,
                            currentBranch.takeIf { branchOptions.contains(it) } ?: branchOptions.firstOrNull(),
                        )

                        setWorkflowOptions(workflows, workflows.firstOrNull { it.isDefaultDeployWorkflow } ?: workflows.firstOrNull())
                        renderInputs(getSelectedWorkflow())
                        outputArea.text = if (workflows.isEmpty()) "No workflow_dispatch workflows were found." else "Ready."
                    }
                    .onFailure { error ->
                        outputArea.text = error.message ?: "Failed to load deploy metadata."
                    }
                setBusy(false)
            }
        }
    }

    private fun renderInputs(workflow: WorkflowMetadata?) {
        inputsPanel.removeAll()
        inputFields.clear()

        val entries = workflow?.inputs.orEmpty().filterKeys { it != workflow?.branchInputName }.entries.toList()
        if (entries.isEmpty()) {
            inputsPanel.add(JLabel("This workflow does not define additional inputs."), gridConstraints(0, 0))
            inputsPanel.revalidate()
            inputsPanel.repaint()
            return
        }

        entries.forEachIndexed { index, (name, input) ->
            val field = createInputField(input)
            inputFields[name] = field
            inputsPanel.add(JLabel("$name${if (input.required) " *" else ""}"), gridConstraints(0, index))
            inputsPanel.add(field, gridConstraints(1, index, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))
        }

        inputsPanel.revalidate()
        inputsPanel.repaint()
    }

    private fun createInputField(input: WorkflowInput): JComponent {
        if (input.type == "choice" && input.options.isNotEmpty()) {
            return createSearchableComboBox().apply {
                setComboOptions(
                    this,
                    input.options,
                    input.default?.toString()?.takeIf { input.options.contains(it) } ?: input.options.firstOrNull(),
                )
            }
        }

        if (input.type == "boolean") {
            return createSearchableComboBox().apply {
                setComboOptions(this, listOf("false", "true"), input.default?.toString() ?: "false")
            }
        }

        return JTextField(input.default?.toString() ?: "").apply {
            toolTipText = input.description ?: ""
        }
    }

    private fun runDeploy() {
        val branch = branchCombo.selectedItem?.toString().orEmpty()
        val workflow = getSelectedWorkflow()?.file.orEmpty()
        if (branch.isBlank() || workflow.isBlank()) {
            outputArea.text = "Target branch and workflow are required."
            return
        }

        setBusy(true)
        activeRunId = null
        lastRunStatusText = ""
        workflowStatusLabel.text = "Workflow status: Triggering"
        executionResultLabel.text = "Execution result: Waiting for GitHub Actions run..."
        outputArea.text = "Triggering deploy workflow..."
        val inputs = collectInputs()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.runDeploy(branch, workflow, inputs, dispatch = true) }
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = {
                        outputArea.text = formatResult(it)
                        if (it.status == 0 && it.run?.databaseId != null) {
                            startPollingRun(it.run)
                        } else {
                            workflowStatusLabel.text = "Workflow status: Failed"
                            executionResultLabel.text = "Execution result: Workflow was not triggered."
                            setBusy(false)
                        }
                    },
                    onFailure = { it.message ?: "Deploy command failed." },
                ).let { message ->
                    if (message is String) {
                        outputArea.text = message
                        workflowStatusLabel.text = "Workflow status: Failed"
                        executionResultLabel.text = "Execution result: Deploy command failed."
                        setBusy(false)
                    }
                }
            }
        }
    }

    private fun startPollingRun(run: WorkflowRunStatus) {
        val runId = run.databaseId ?: return
        val token = pollToken + 1
        pollToken = token
        activeRunId = runId
        updateRunStatus(run)
        setBusy(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            while (pollToken == token) {
                val result = runCatching { service.getRunStatus(runId) }
                var shouldContinue = true

                SwingUtilities.invokeAndWait {
                    result
                        .onSuccess { status ->
                            updateRunStatus(status)
                            shouldContinue = status.status != "completed"
                        }
                        .onFailure { error ->
                            workflowStatusLabel.text = "Workflow status: Unknown"
                            executionResultLabel.text = "Execution result: ${error.message ?: "Failed to load workflow status."}"
                            outputArea.append("\n${error.message ?: "Failed to load workflow status."}")
                            shouldContinue = false
                        }
                }

                if (!shouldContinue) break
                Thread.sleep(5000)
            }

            SwingUtilities.invokeLater {
                if (pollToken == token) {
                    activeRunId = null
                    setBusy(false)
                }
            }
        }
    }

    private fun cancelActiveRun() {
        val runId = activeRunId ?: return
        cancelButton.isEnabled = false
        executionResultLabel.text = "Execution result: Cancelling workflow run..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.cancelRun(runId) }
            SwingUtilities.invokeLater {
                result
                    .onSuccess {
                        val output = listOf(it.stdout, it.stderr).filter { text -> text.isNotBlank() }.joinToString("\n")
                        if (it.status == 0) {
                            workflowStatusLabel.text = "Workflow status: Cancelling"
                            executionResultLabel.text = "Execution result: Cancel requested."
                            outputArea.append("\nCancel requested.${if (output.isBlank()) "" else "\n$output"}")
                        } else {
                            executionResultLabel.text = "Execution result: Cancel failed."
                            outputArea.append("\nCancel failed.${if (output.isBlank()) "" else "\n$output"}")
                            cancelButton.isEnabled = true
                        }
                    }
                    .onFailure { error ->
                        executionResultLabel.text = "Execution result: Cancel failed."
                        outputArea.append("\n${error.message ?: "Cancel command failed."}")
                        cancelButton.isEnabled = true
                    }
            }
        }
    }

    private fun collectInputs(): Map<String, String> {
        return inputFields.mapValues { (_, field) ->
            when (field) {
                is JTextField -> field.text.trim()
                is JComboBox<*> -> field.selectedItem?.toString().orEmpty()
                else -> ""
            }
        }
    }

    private fun formatResult(result: DeployCommandResult): String {
        val parsedMessage = formatParsedMessage(result.parsed)
        val rawOutput = listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n\n").trim()
        val status = if (result.status == 0) {
            "Deploy workflow triggered successfully."
        } else {
            "Deploy workflow failed to trigger."
        }

        return listOf(
            status,
            "",
            "Status: ${if (result.status == 0) "Success" else "Failed"}",
            "Command: ${result.commandPreview}",
            result.run?.url?.let { "Run URL: $it" }.orEmpty(),
            parsedMessage.ifBlank { rawOutput },
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun formatParsedMessage(parsed: JsonObject?): String {
        if (parsed == null) return ""
        val lines = mutableListOf<String>()
        parsed.get("reason")?.asString?.let { lines.add(it) }
        parsed.get("commandPreview")?.asString?.let { lines.add("Command Preview: $it") }
        parsed.get("dispatch")
            .asJsonObjectOrNull()
            ?.let { dispatch ->
                dispatch.get("stderr")?.asNonBlankStringOrNull()?.let { lines.add("Dispatch error: $it") }
                dispatch.get("stdout")?.asNonBlankStringOrNull()?.let { lines.add("Dispatch output: $it") }
            }
        parsed.get("dispatch")
            .asJsonObjectOrNull()
            ?.get("run")
            .asJsonObjectOrNull()
            ?.get("url")
            .asNonBlankStringOrNull()
            ?.let { lines.add("Run URL: $it") }
        parsed.get("missingRequiredInputs")?.takeIf { it is JsonArray && it.size() > 0 }?.let {
            lines.add("Missing required inputs: $it")
        }
        parsed.get("remediation")?.takeIf { it is JsonArray }?.asJsonArray?.forEach { lines.add(it.asString) }
        return lines.joinToString("\n")
    }

    private fun getSelectedWorkflow(): WorkflowMetadata? {
        return (workflowCombo.selectedItem as? WorkflowComboItem)?.workflow ?: workflows.firstOrNull()
    }

    private fun setBusy(isBusy: Boolean) {
        refreshButton.isEnabled = !isBusy
        runButton.isEnabled = !isBusy
        cancelButton.isEnabled = isBusy && activeRunId != null
    }

    private fun updateRunStatus(run: WorkflowRunStatus) {
        val status = run.status ?: "unknown"
        val conclusion = run.conclusion?.takeIf { it.isNotBlank() }
        val statusText = "Workflow status: ${conclusion ?: status}"
        workflowStatusLabel.text = "Workflow status: ${conclusion ?: status}"
        executionResultLabel.text = when {
            status == "completed" && conclusion != null -> "Execution result: $conclusion"
            run.url != null -> "Execution result: Running - ${run.url}"
            else -> "Execution result: Running"
        }
        if (lastRunStatusText != statusText) {
            lastRunStatusText = statusText
            outputArea.append(
                listOf(
                    "",
                    statusText,
                    run.displayTitle?.let { "Display title: $it" }.orEmpty(),
                    run.url?.let { "Run URL: $it" }.orEmpty(),
                )
                    .filter { it.isNotBlank() }
                    .joinToString("\n"),
            )
        }
    }

    private fun addFormRow(panel: JPanel, row: Int, label: String, component: JComponent) {
        panel.add(JLabel(label), gridConstraints(0, row))
        panel.add(component, gridConstraints(1, row, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))
    }

    private fun createSearchableComboBox(): JComboBox<String> {
        return JComboBox<String>().apply {
            maximumRowCount = 12
            object : ComboboxSpeedSearch(this) {
                override fun getElementText(element: Any?): String {
                    return element?.toString().orEmpty()
                }
            }
        }
    }

    private fun createSearchableWorkflowComboBox(): JComboBox<WorkflowComboItem> {
        return JComboBox<WorkflowComboItem>().apply {
            maximumRowCount = 12
            object : ComboboxSpeedSearch(this) {
                override fun getElementText(element: Any?): String {
                    return element?.toString().orEmpty()
                }
            }
        }
    }

    private fun setComboOptions(comboBox: JComboBox<String>, options: List<String>, selected: String?) {
        comboBox.removeAllItems()
        options.distinct().forEach { comboBox.addItem(it) }
        comboBox.selectedItem = selected ?: options.firstOrNull()
    }

    private fun setWorkflowOptions(options: List<WorkflowMetadata>, selected: WorkflowMetadata?) {
        workflowCombo.removeAllItems()
        options.map { WorkflowComboItem(it) }.forEach { workflowCombo.addItem(it) }
        workflowCombo.selectedItem = selected?.let { WorkflowComboItem(it) } ?: workflowCombo.getItemAt(0)
    }

    private fun gridConstraints(
        x: Int,
        y: Int,
        fill: Int = GridBagConstraints.NONE,
        weightX: Double = 0.0,
    ): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = x
            gridy = y
            this.fill = fill
            this.weightx = weightX
            insets = Insets(6, 6, 6, 6)
            anchor = GridBagConstraints.NORTHWEST
        }
    }
}

private data class WorkflowComboItem(val workflow: WorkflowMetadata) {
    override fun toString(): String {
        return workflow.name.ifBlank { workflow.file }
    }
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
    return this?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonElement?.asNonBlankStringOrNull(): String? {
    return this
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.takeIf { it.isNotBlank() }
}

