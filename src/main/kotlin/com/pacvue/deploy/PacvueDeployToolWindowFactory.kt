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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private val historyCache = DeployHistoryCache(project)
    private val branchCombo = createSearchableComboBox()
    private val workflowCombo = createSearchableWorkflowComboBox()
    private val inputsPanel = JPanel(GridBagLayout())
    private val historyPanel = JPanel(BorderLayout(0, 4))
    private val historyRowsPanel = JPanel(GridBagLayout())
    private val clearHistoryButton = JButton("Clear")
    private val outputArea = JTextArea("Loading deploy metadata...")
    private val workflowStatusLabel = JLabel("Workflow status: Idle")
    private val executionResultLabel = JLabel("Execution result: Not started")
    private val refreshButton = JButton("Refresh")
    private val runButton = JButton("Run")
    private val cancelButton = JButton("Cancel")
    private val inputFields = linkedMapOf<String, JComponent>()
    private var workflows = emptyList<WorkflowMetadata>()
    private var currentRepoId = ""
    private var deployHistory = emptyList<DeployHistoryEntry>()
    private var lastRunStatusText = ""
    private var suppressSelectionEvents = false
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

        val historyHeader = JPanel(BorderLayout())
        historyHeader.add(JLabel("Recent Deploys"), BorderLayout.WEST)
        historyHeader.add(clearHistoryButton, BorderLayout.EAST)
        historyPanel.border = JBUI.Borders.empty(4, 0, 4, 0)
        historyPanel.add(historyHeader, BorderLayout.NORTH)
        historyPanel.add(historyRowsPanel, BorderLayout.CENTER)
        historyPanel.isVisible = false

        val actionsPanel = JPanel()
        actionsPanel.border = JBUI.Borders.empty(4, 0, 8, 0)
        actionsPanel.add(refreshButton)
        actionsPanel.add(runButton)
        actionsPanel.add(cancelButton)

        val topPanel = JPanel(BorderLayout(0, 8))
        topPanel.add(formPanel, BorderLayout.NORTH)
        topPanel.add(historyPanel, BorderLayout.CENTER)
        topPanel.add(actionsPanel, BorderLayout.SOUTH)

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(outputArea), BorderLayout.CENTER)

        refreshButton.addActionListener { refreshState() }
        workflowCombo.addActionListener { if (!suppressSelectionEvents) renderInputs(getSelectedWorkflow()) }
        branchCombo.addActionListener { if (!suppressSelectionEvents) renderInputs(getSelectedWorkflow()) }
        runButton.addActionListener { runDeploy() }
        cancelButton.addActionListener { cancelActiveRun() }
        clearHistoryButton.addActionListener { clearDeployHistory() }

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
                DeployPanelState(
                    repoId = service.getRepoId(),
                    currentBranch = currentBranch,
                    branchOptions = branchOptions,
                    workflows = workflowMetadata.workflows,
                )
            }

            SwingUtilities.invokeLater {
                result
                    .onSuccess { state ->
                        currentRepoId = state.repoId
                        workflows = state.workflows
                        deployHistory = historyCache.getRecentDeploys(currentRepoId)
                        withSelectionEventsSuppressed {
                            setComboOptions(
                                branchCombo,
                                state.branchOptions,
                                state.currentBranch.takeIf { state.branchOptions.contains(it) } ?: state.branchOptions.firstOrNull(),
                            )
                            setWorkflowOptions(workflows, workflows.firstOrNull { it.isDefaultDeployWorkflow } ?: workflows.firstOrNull())
                        }
                        renderInputs(getSelectedWorkflow())
                        renderHistory()
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
        applyCachedInputs(workflow)
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

    private fun renderHistory() {
        historyRowsPanel.removeAll()
        historyPanel.isVisible = deployHistory.isNotEmpty()
        clearHistoryButton.isEnabled = deployHistory.isNotEmpty()

        deployHistory.forEachIndexed { index, entry ->
            val row = JPanel(BorderLayout(8, 0))
            row.border = JBUI.Borders.empty(4, 0)

            val summaryPanel = JPanel(GridBagLayout())
            val title = "${entry.workflowName.ifBlank { entry.workflow }} @ ${entry.branch.ifBlank { "branch" }}"
            val metaLabel = JLabel("$title · ${formatHistoryTime(entry.ts)}")
            metaLabel.toolTipText = "${entry.repoId}\n${entry.workflow}"
            val inputsLabel = JLabel(formatHistoryInputs(entry.inputs))
            inputsLabel.toolTipText = inputsLabel.text

            summaryPanel.add(metaLabel, gridConstraints(0, 0, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))
            summaryPanel.add(inputsLabel, gridConstraints(0, 1, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))

            val reuseButton = JButton("Reuse")
            reuseButton.addActionListener { applyHistoryEntry(entry) }

            row.add(summaryPanel, BorderLayout.CENTER)
            row.add(reuseButton, BorderLayout.EAST)
            historyRowsPanel.add(row, gridConstraints(0, index, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))
        }

        historyPanel.revalidate()
        historyPanel.repaint()
    }

    private fun applyCachedInputs(workflow: WorkflowMetadata?) {
        val branch = branchCombo.selectedItem?.toString().orEmpty()
        val workflowFile = workflow?.file.orEmpty()
        val cached = historyCache.findRecentDeploy(currentRepoId, branch, workflowFile) ?: return

        applyInputValues(cached.inputs)
    }

    private fun applyInputValues(inputs: Map<String, String>) {
        inputs.forEach { (name, value) ->
            val field = inputFields[name] ?: return@forEach
            when (field) {
                is JTextField -> field.text = value
                is JComboBox<*> -> selectComboValue(field, value)
            }
        }
    }

    private fun selectComboValue(comboBox: JComboBox<*>, value: String) {
        @Suppress("UNCHECKED_CAST")
        val typedComboBox = comboBox as JComboBox<Any>
        val existingIndex = (0 until typedComboBox.itemCount).firstOrNull { typedComboBox.getItemAt(it)?.toString() == value }
        if (existingIndex == null && value.isNotBlank()) {
            typedComboBox.addItem(value)
        }
        typedComboBox.selectedItem = value
    }

    private fun applyHistoryEntry(entry: DeployHistoryEntry) {
        val workflow = workflows.find { it.file == entry.workflow } ?: WorkflowMetadata(file = entry.workflow, name = entry.workflowName)

        withSelectionEventsSuppressed {
            ensureComboValue(branchCombo, entry.branch)
            branchCombo.selectedItem = entry.branch
            selectWorkflowOption(workflow)
        }

        renderInputs(workflow)
        applyInputValues(entry.inputs)
        outputArea.text = listOf(
            "Recent deploy config applied.",
            "Branch: ${entry.branch}",
            "Workflow: ${entry.workflowName.ifBlank { entry.workflow }}",
            "Inputs: ${formatHistoryInputs(entry.inputs)}",
        ).joinToString("\n")
    }

    private fun clearDeployHistory() {
        if (currentRepoId.isBlank()) return

        historyCache.clear(currentRepoId)
        deployHistory = emptyList()
        renderHistory()
        outputArea.text = "Recent Deploys cleared for current project."
    }

    private fun runDeploy() {
        val branch = branchCombo.selectedItem?.toString().orEmpty()
        val selectedWorkflow = getSelectedWorkflow()
        val workflow = selectedWorkflow?.file.orEmpty()
        val workflowName = selectedWorkflow?.name?.takeIf { it.isNotBlank() } ?: workflow
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
            val result = runCatching {
                val deployResult = service.runDeploy(branch, workflow, inputs, dispatch = true)
                val repoId = if (deployResult.status == 0 && deployResult.run?.databaseId != null) service.getRepoId() else ""
                deployResult to repoId
            }
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { (deployResult, repoId) ->
                        outputArea.text = formatResult(deployResult)
                        if (deployResult.status == 0 && deployResult.run?.databaseId != null) {
                            deployHistory = historyCache.append(
                                repoId = repoId,
                                branch = branch,
                                workflow = workflow,
                                workflowName = workflowName,
                                inputs = inputs,
                            )
                            renderHistory()
                            startPollingRun(deployResult.run)
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
        workflowCombo.selectedItem = selected?.let { WorkflowComboItem(it) } ?: if (workflowCombo.itemCount > 0) workflowCombo.getItemAt(0) else null
    }

    private fun ensureComboValue(comboBox: JComboBox<String>, value: String) {
        if (value.isBlank()) return
        val exists = (0 until comboBox.itemCount).any { comboBox.getItemAt(it) == value }
        if (!exists) {
            comboBox.addItem(value)
        }
    }

    private fun selectWorkflowOption(workflow: WorkflowMetadata) {
        val existing = (0 until workflowCombo.itemCount)
            .map { workflowCombo.getItemAt(it) }
            .firstOrNull { it.workflow.file == workflow.file }
        val item = existing ?: WorkflowComboItem(workflow).also { workflowCombo.addItem(it) }
        workflowCombo.selectedItem = item
    }

    private fun withSelectionEventsSuppressed(action: () -> Unit) {
        suppressSelectionEvents = true
        try {
            action()
        } finally {
            suppressSelectionEvents = false
        }
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

private val historyTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())

private data class WorkflowComboItem(val workflow: WorkflowMetadata) {
    override fun toString(): String {
        return workflow.name.ifBlank { workflow.file }
    }
}

private data class DeployPanelState(
    val repoId: String,
    val currentBranch: String,
    val branchOptions: List<String>,
    val workflows: List<WorkflowMetadata>,
)

private fun formatHistoryTime(ts: Long): String {
    return runCatching { historyTimeFormat.format(Instant.ofEpochMilli(ts)) }.getOrDefault("")
}

private fun formatHistoryInputs(inputs: Map<String, String>): String {
    val text = inputs
        .filterValues { it.isNotBlank() }
        .entries
        .joinToString(", ") { (key, value) -> "$key=$value" }
        .ifBlank { "No inputs" }
    return text.take(180)
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
