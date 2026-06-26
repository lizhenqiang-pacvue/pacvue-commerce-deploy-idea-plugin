package com.pacvue.deploy

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
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
    private val project = project
    private val service = DeployService(project)
    private val historyCache = DeployHistoryCache(project)
    private val branchCombo = createSearchableComboBox()
    private val workflowCombo = createSearchableWorkflowComboBox()
    private val inputsPanel = JPanel(GridBagLayout())
    private val historyPanel = JPanel(BorderLayout(0, 4))
    private val historyRowsPanel = JPanel(GridBagLayout())
    private val historyScrollPane = JBScrollPane(historyRowsPanel).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    private val historyToggleButton = JButton("Recent Deploys")
    private val outputArea = JTextArea("Loading deploy metadata...")
    private val refreshButton = JButton("Refresh")
    private val runButton = JButton("Run")
    private val inputFields = linkedMapOf<String, JComponent>()
    private var workflows = emptyList<WorkflowMetadata>()
    private var currentRepoId = ""
    private var deployHistory = emptyList<DeployHistoryEntry>()
    private var historyExpanded = true
    private var lastRunStatusText = ""
    private var suppressSelectionEvents = false
    @Volatile private var activeRunId: Long? = null
    private val notifiedSuccessRunIds = mutableSetOf<Long>()
    private val reportedFailureIssueRunIds = mutableSetOf<Long>()

    init {
        border = JBUI.Borders.empty(12)

        outputArea.isEditable = false
        outputArea.lineWrap = true
        outputArea.wrapStyleWord = true
        outputArea.margin = JBUI.insets(8, 10)

        val formPanel = JPanel(GridBagLayout())
        addFormRow(formPanel, 0, "Target branch", branchCombo)
        addFormRow(formPanel, 1, "Workflow", workflowCombo)
        addFormRow(formPanel, 2, "Inputs", inputsPanel, labelAnchor = GridBagConstraints.NORTHWEST)

        val historyHeader = JPanel(BorderLayout())
        historyHeader.add(historyToggleButton, BorderLayout.WEST)
        historyPanel.border = JBUI.Borders.empty(4, 0, 4, 0)
        historyPanel.add(historyHeader, BorderLayout.NORTH)
        historyPanel.add(historyScrollPane, BorderLayout.CENTER)
        historyPanel.isVisible = false

        val actionsPanel = JPanel()
        actionsPanel.border = JBUI.Borders.empty(4, 0, 8, 0)
        actionsPanel.add(refreshButton)
        actionsPanel.add(runButton)

        val topPanel = JPanel(BorderLayout(0, 8))
        topPanel.add(formPanel, BorderLayout.NORTH)
        topPanel.add(historyPanel, BorderLayout.CENTER)
        topPanel.add(actionsPanel, BorderLayout.SOUTH)

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(outputArea), BorderLayout.CENTER)

        refreshButton.addActionListener { refreshState() }
        workflowCombo.addActionListener { if (!suppressSelectionEvents) renderInputs(getSelectedWorkflow()) }
        branchCombo.addActionListener { if (!suppressSelectionEvents) renderInputs(getSelectedWorkflow()) }
        runButton.addActionListener { confirmAndRunDeploy() }
        historyToggleButton.addActionListener {
            historyExpanded = !historyExpanded
            renderHistory()
        }

        setBusy(true)
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
        historyToggleButton.text = "${if (historyExpanded) "-" else "+"} Recent Deploys (${deployHistory.size})"
        historyScrollPane.isVisible = historyExpanded

        if (historyExpanded) {
            deployHistory.forEachIndexed { index, entry ->
                val row = JPanel(BorderLayout(8, 0))
                row.border = JBUI.Borders.empty(4, 0)
                row.preferredSize = Dimension(0, HISTORY_ROW_HEIGHT)
                row.minimumSize = Dimension(0, HISTORY_ROW_HEIGHT)
                row.maximumSize = Dimension(Int.MAX_VALUE, HISTORY_ROW_HEIGHT)

                val summaryPanel = createHistorySummaryPanel(entry)

                val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
                actionPanel.add(createHistoryStatusLabel(entry))
                actionPanel.add(createHistoryActionButton("Reuse") { applyHistoryEntry(entry) })
                actionPanel.add(createHistoryActionButton("Open Run", width = HISTORY_OPEN_RUN_BUTTON_WIDTH, enabled = entry.runUrl.isNullOrBlank().not()) {
                    openHistoryRun(entry)
                })
                if (canCancelHistoryEntry(entry)) {
                    actionPanel.add(createHistoryActionButton("Cancel") { cancelHistoryEntry(entry) })
                } else if (canClearHistoryEntry(entry)) {
                    actionPanel.add(createHistoryActionButton("Clear") { clearHistoryEntry(entry) })
                }

                row.add(summaryPanel, BorderLayout.CENTER)
                row.add(actionPanel, BorderLayout.EAST)
                historyRowsPanel.add(row, historyRowConstraints(index))
            }
        }

        updateHistoryScrollSize()
        historyPanel.revalidate()
        historyPanel.repaint()
    }

    private fun updateHistoryScrollSize() {
        val visibleRows = if (historyExpanded) deployHistory.size.coerceAtMost(HISTORY_MAX_VISIBLE_ROWS) else 0
        val height = visibleRows * (HISTORY_ROW_HEIGHT + HISTORY_ROW_GAP)

        historyScrollPane.preferredSize = Dimension(0, height)
        historyScrollPane.minimumSize = Dimension(0, 0)
        historyScrollPane.maximumSize = Dimension(Int.MAX_VALUE, height)
        historyScrollPane.verticalScrollBarPolicy = if (deployHistory.size > HISTORY_MAX_VISIBLE_ROWS) {
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        } else {
            JScrollPane.VERTICAL_SCROLLBAR_NEVER
        }
    }

    private fun createHistoryActionButton(
        text: String,
        width: Int = HISTORY_BUTTON_WIDTH,
        enabled: Boolean = true,
        action: () -> Unit,
    ): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(width, HISTORY_BUTTON_HEIGHT)
            minimumSize = Dimension(width, HISTORY_BUTTON_HEIGHT)
            isEnabled = enabled
            addActionListener { action() }
        }
    }

    private fun createHistorySummaryPanel(entry: DeployHistoryEntry): JPanel {
        val title = "${entry.workflowName.ifBlank { entry.workflow }} @ ${entry.branch.ifBlank { "branch" }} · ${formatHistoryTime(entry.ts)}"
        val inputs = formatHistoryInputs(entry.inputs)
        val tooltip = listOf(entry.repoId, entry.workflow, inputs).joinToString("\n")

        return JPanel(GridBagLayout()).apply {
            toolTipText = tooltip
            add(
                JLabel(title).apply {
                    toolTipText = tooltip
                },
                historySummaryConstraints(0),
            )
            add(
                JLabel(inputs).apply {
                    font = font.deriveFont(Font.PLAIN, font.size2D - 1)
                    toolTipText = tooltip
                },
                historySummaryConstraints(1),
            )
        }
    }

    private fun createHistoryStatusLabel(entry: DeployHistoryEntry): JLabel {
        return JLabel(formatHistoryStatus(entry), SwingConstants.CENTER).apply {
            preferredSize = Dimension(HISTORY_STATUS_WIDTH, HISTORY_BUTTON_HEIGHT)
            minimumSize = Dimension(HISTORY_STATUS_WIDTH, HISTORY_BUTTON_HEIGHT)
            toolTipText = formatHistoryStatusTooltip(entry)
        }
    }

    private fun canCancelHistoryEntry(entry: DeployHistoryEntry): Boolean {
        return entry.runId != null && entry.status == "in_progress"
    }

    private fun canClearHistoryEntry(entry: DeployHistoryEntry): Boolean {
        return entry.status != "in_progress" && entry.status != "cancelling"
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

    private fun clearHistoryEntry(entry: DeployHistoryEntry) {
        if (currentRepoId.isBlank() || entry.id.isBlank()) return

        deployHistory = historyCache.remove(currentRepoId, entry.id)
        renderHistory()
        outputArea.text = "Recent deploy entry cleared."
    }

    private fun cancelHistoryEntry(entry: DeployHistoryEntry) {
        val runId = entry.runId ?: return
        cancelRun(runId, entry.runUrl, previousStatus = entry.status)
    }

    private fun openHistoryRun(entry: DeployHistoryEntry) {
        entry.runUrl?.takeIf { it.isNotBlank() }?.let { BrowserUtil.browse(it) }
    }

    private fun confirmAndRunDeploy() {
        val branch = branchCombo.selectedItem?.toString().orEmpty()
        val selectedWorkflow = getSelectedWorkflow()
        val workflow = selectedWorkflow?.file.orEmpty()
        val workflowName = selectedWorkflow?.name?.takeIf { it.isNotBlank() } ?: workflow
        if (branch.isBlank() || workflow.isBlank()) {
            outputArea.text = "Target branch and workflow are required."
            return
        }

        val inputs = collectInputs()
        val inputErrors = validateDeployInputs(selectedWorkflow, inputs)
        markInputValidation(inputErrors)
        if (inputErrors.isNotEmpty()) {
            outputArea.text = formatInputValidationErrors(inputErrors)
            inputFields[inputErrors.first().name]?.requestFocusInWindow()
            return
        }

        val confirmed = DeployConfirmDialog(
            project = project,
            branch = branch,
            workflowName = workflowName,
            workflowFile = workflow,
            inputs = inputs,
            activeRunId = activeRunId,
        ).showAndGet()
        if (!confirmed) return

        runDeploy(branch, workflow, workflowName, inputs)
    }

    private fun runDeploy(branch: String, workflow: String, workflowName: String, inputs: Map<String, String>) {
        setBusy(true)
        activeRunId = null
        lastRunStatusText = ""
        outputArea.text = "Triggering deploy workflow..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val deployResult = service.runDeploy(branch, workflow, inputs, dispatch = true)
                val repoId = service.getRepoId()
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
                                run = deployResult.run,
                            )
                            renderHistory()
                            startPollingRun(deployResult.run)
                        } else {
                            outputArea.append("\nWorkflow was not triggered.")
                            reportDeployTriggerFailureIssue(
                                branch = branch,
                                workflow = workflow,
                                workflowName = workflowName,
                                inputs = inputs,
                                deployResult = deployResult,
                                repoId = repoId,
                            )
                            setBusy(false)
                        }
                    },
                    onFailure = { it.message ?: "Deploy command failed." },
                ).let { message ->
                    if (message is String) {
                        outputArea.text = message
                        setBusy(false)
                    }
                }
            }
        }
    }

    private fun startPollingRun(run: WorkflowRunStatus) {
        val runId = run.databaseId ?: return
        activeRunId = runId
        updateRunStatus(run, updateCurrentOutput = true)
        setBusy(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            while (true) {
                val result = runCatching { service.getRunStatus(runId) }
                var shouldContinue = true

                SwingUtilities.invokeAndWait {
                    result.onSuccess { status ->
                        updateRunStatus(status, updateCurrentOutput = activeRunId == runId)
                        shouldContinue = status.status != "completed"
                    }.onFailure { error ->
                        if (activeRunId == runId) {
                            outputArea.append("\n${error.message ?: "Failed to load workflow status."}")
                        }
                        shouldContinue = false
                    }
                }

                if (!shouldContinue) break
                Thread.sleep(5000)
            }

            SwingUtilities.invokeLater {
                if (activeRunId == runId) {
                    activeRunId = null
                    setBusy(false)
                }
            }
        }
    }

    private fun cancelRun(runId: Long, runUrl: String?, previousStatus: String?) {
        val cancellingRun = WorkflowRunStatus(databaseId = runId, status = "cancelling", url = runUrl)
        updateHistoryRunStatus(cancellingRun)

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.cancelRun(runId) }
            SwingUtilities.invokeLater {
                result
                    .onSuccess {
                        val output = listOf(it.stdout, it.stderr).filter { text -> text.isNotBlank() }.joinToString("\n")
                        if (it.status == 0) {
                            updateHistoryRunStatus(cancellingRun)
                            outputArea.append("\nCancel requested.${if (output.isBlank()) "" else "\n$output"}")
                        } else {
                            updateHistoryRunStatus(WorkflowRunStatus(databaseId = runId, status = previousStatus ?: "in_progress", url = runUrl))
                            outputArea.append("\nCancel failed.${if (output.isBlank()) "" else "\n$output"}")
                            setBusy(false)
                        }
                    }
                    .onFailure { error ->
                        updateHistoryRunStatus(WorkflowRunStatus(databaseId = runId, status = previousStatus ?: "in_progress", url = runUrl))
                        outputArea.append("\n${error.message ?: "Cancel command failed."}")
                        setBusy(false)
                    }
            }
        }
    }

    private fun validateDeployInputs(workflow: WorkflowMetadata?, inputs: Map<String, String>): List<InputValidationError> {
        if (workflow == null) return emptyList()

        return workflow.inputs
            .filterKeys { it != workflow.branchInputName }
            .mapNotNull { (name, input) ->
                val value = inputs[name].orEmpty()
                when {
                    input.required && value.isBlank() -> InputValidationError(name, "$name is required.")
                    input.type == "choice" &&
                        input.options.isNotEmpty() &&
                        value.isNotBlank() &&
                        input.options.none { it == value } ->
                        InputValidationError(name, "$name must be one of: ${input.options.joinToString(", ")}")
                    input.type == "boolean" &&
                        value.isNotBlank() &&
                        value != "true" &&
                        value != "false" ->
                        InputValidationError(name, "$name must be true or false.")
                    else -> null
                }
            }
    }

    private fun markInputValidation(errors: List<InputValidationError>) {
        val errorNames = errors.map { it.name }.toSet()
        inputFields.forEach { (name, field) ->
            field.putClientProperty("JComponent.outline", if (name in errorNames) "error" else null)
        }
    }

    private fun formatInputValidationErrors(errors: List<InputValidationError>): String {
        return listOf("Please fix deploy inputs before running:", *errors.map { "- ${it.message}" }.toTypedArray())
            .joinToString("\n")
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
    }

    private fun updateRunStatus(run: WorkflowRunStatus, updateCurrentOutput: Boolean = true) {
        val status = run.status ?: "unknown"
        val conclusion = run.conclusion?.takeIf { it.isNotBlank() }
        updateHistoryRunStatus(run)

        if (updateCurrentOutput) {
            val displayStatus = conclusion ?: status
            val statusText = "Workflow status: $displayStatus"
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

        if (
            run.databaseId != null &&
            status == "completed" &&
            conclusion.equals("success", ignoreCase = true) &&
            notifiedSuccessRunIds.add(run.databaseId)
        ) {
            showDeploySuccessNotification(run)
        }

        if (
            run.databaseId != null &&
            status == "completed" &&
            conclusion != null &&
            !conclusion.equals("success", ignoreCase = true) &&
            !conclusion.equals("cancelled", ignoreCase = true) &&
            reportedFailureIssueRunIds.add(run.databaseId)
        ) {
            reportDeployRunFailureIssue(run)
        }
    }

    private fun updateHistoryRunStatus(run: WorkflowRunStatus) {
        if (currentRepoId.isBlank() || run.databaseId == null) return

        deployHistory = historyCache.updateRunStatus(currentRepoId, run)
        renderHistory()
    }

    private fun showDeploySuccessNotification(run: WorkflowRunStatus) {
        val title = run.displayTitle?.takeIf { it.isNotBlank() } ?: "Run #${run.databaseId}"
        val runUrl = run.url?.takeIf { it.isNotBlank() }
        val content = listOf(
            "$title completed successfully.",
            runUrl?.let { "Run URL: $it" }.orEmpty(),
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification("Pacvue Deploy completed", content, NotificationType.INFORMATION)
            .apply {
                if (runUrl != null) {
                    addAction(NotificationAction.createSimpleExpiring("Open Run") {
                        BrowserUtil.browse(runUrl)
                    })
                    addAction(NotificationAction.createSimple("Copy URL") {
                        copyText(runUrl)
                        showInfoNotification("Pacvue Deploy", "Run URL copied to clipboard.")
                    })
                }
            }
            .notify(project)
    }

    private fun showInfoNotification(title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun reportDeployTriggerFailureIssue(
        branch: String,
        workflow: String,
        workflowName: String,
        inputs: Map<String, String>,
        deployResult: DeployCommandResult,
        repoId: String,
    ) {
        if (!shouldCreateFailureIssues()) return

        outputArea.append("\nCreating deploy failure issue...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val issueResult = service.createDeployFailureIssue(
                DeployFailureIssueRequest(
                    failureType = "trigger",
                    commerceRepo = repoId.ifBlank { currentRepoId.ifBlank { "unknown" } },
                    targetBranch = branch,
                    workflow = workflow,
                    workflowName = workflowName,
                    inputs = inputs,
                    command = deployResult.commandPreview,
                    errorMessage = buildDeployIssueErrorMessage(deployResult),
                    run = deployResult.run,
                    parsed = deployResult.parsed,
                ),
            )
            SwingUtilities.invokeLater { appendIssueResult(issueResult) }
        }
    }

    private fun reportDeployRunFailureIssue(run: WorkflowRunStatus) {
        if (!shouldCreateFailureIssues()) return

        val runId = run.databaseId ?: return
        val entry = deployHistory.firstOrNull { it.runId == runId }
        outputArea.append("\nCreating deploy failure issue for run #$runId...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val jobsSummary = runCatching { service.getRunJobsSummary(runId) }.getOrDefault(emptyList())
            val issueResult = service.createDeployFailureIssue(
                DeployFailureIssueRequest(
                    failureType = "run",
                    commerceRepo = currentRepoId.ifBlank { service.getRepoId() },
                    targetBranch = entry?.branch.orEmpty(),
                    workflow = entry?.workflow.orEmpty(),
                    workflowName = entry?.workflowName.orEmpty(),
                    inputs = entry?.inputs.orEmpty(),
                    command = null,
                    errorMessage = "Workflow run #$runId completed with conclusion: ${run.conclusion ?: "unknown"}",
                    run = run,
                    parsed = null,
                    jobsSummary = jobsSummary,
                ),
            )
            SwingUtilities.invokeLater { appendIssueResult(issueResult) }
        }
    }

    private fun appendIssueResult(result: DeployIssueResult) {
        if (result.ok) {
            outputArea.append("\nDeploy failure reported as GitHub issue.${result.issueUrl?.let { "\nIssue URL: $it" }.orEmpty()}")
            return
        }

        outputArea.append("\nFailed to create deploy failure issue: ${result.error ?: "Unknown error."}")
    }

    private fun buildDeployIssueErrorMessage(result: DeployCommandResult): String {
        val parsedMessage = formatParsedMessage(result.parsed)
        val rawOutput = listOf(result.stderr, result.stdout).filter { it.isNotBlank() }.joinToString("\n\n").trim()
        return parsedMessage.ifBlank { rawOutput }.ifBlank { "Deploy command failed." }
    }

    private fun shouldCreateFailureIssues(): Boolean {
        return !System.getenv("PACVUE_DEPLOY_CREATE_ISSUE").equals("false", ignoreCase = true)
    }

    private fun addFormRow(
        panel: JPanel,
        row: Int,
        label: String,
        component: JComponent,
        labelAnchor: Int = GridBagConstraints.WEST,
    ) {
        panel.add(JLabel(label), gridConstraints(0, row, anchor = labelAnchor))
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
        anchor: Int = GridBagConstraints.WEST,
    ): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = x
            gridy = y
            this.fill = fill
            this.weightx = weightX
            insets = Insets(6, 6, 6, 6)
            this.anchor = anchor
        }
    }

    private fun historyRowConstraints(y: Int): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = 0
            gridy = y
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, 4, 0)
            anchor = GridBagConstraints.NORTHWEST
        }
    }

    private fun historySummaryConstraints(y: Int): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = 0
            gridy = y
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, if (y == 0) 2 else 0, 0)
            anchor = GridBagConstraints.WEST
        }
    }
}

private val historyTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())

private const val NOTIFICATION_GROUP_ID = "Pacvue Deploy"
private const val HISTORY_MAX_VISIBLE_ROWS = 5
private const val HISTORY_ROW_HEIGHT = 52
private const val HISTORY_ROW_GAP = 4
private const val HISTORY_STATUS_WIDTH = 96
private const val HISTORY_BUTTON_WIDTH = 72
private const val HISTORY_OPEN_RUN_BUTTON_WIDTH = 88
private const val HISTORY_BUTTON_HEIGHT = 28

private data class WorkflowComboItem(val workflow: WorkflowMetadata) {
    override fun toString(): String {
        return workflow.name.ifBlank { workflow.file }
    }
}

private class DeployConfirmDialog(
    project: Project,
    private val branch: String,
    private val workflowName: String,
    private val workflowFile: String,
    private val inputs: Map<String, String>,
    private val activeRunId: Long?,
) : DialogWrapper(project) {
    init {
        title = "Confirm Deploy"
        setOKButtonText("Confirm Deploy")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        var row = 0
        if (activeRunId != null) {
            addConfirmRow(
                panel,
                row++,
                "Active run",
                "Run #$activeRunId is still being tracked. Confirm Deploy will track the new run.",
            )
        }
        addConfirmRow(panel, row++, "Branch", branch)
        addConfirmRow(panel, row++, "Workflow", workflowName)
        addConfirmRow(panel, row++, "File", workflowFile)

        val inputText = formatConfirmInputs(inputs)
        val inputArea = JTextArea(inputText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            margin = JBUI.insets(6)
        }
        val scrollPane = JScrollPane(inputArea).apply {
            preferredSize = Dimension(520, 140)
        }

        panel.add(JLabel("Inputs"), confirmConstraints(0, row, anchor = GridBagConstraints.NORTHWEST))
        panel.add(scrollPane, confirmConstraints(1, row, fill = GridBagConstraints.BOTH, weightX = 1.0, weightY = 1.0))
        return panel
    }

    private fun addConfirmRow(panel: JPanel, row: Int, label: String, value: String) {
        val valueLabel = JLabel(value.ifBlank { "-" })
        valueLabel.toolTipText = valueLabel.text
        panel.add(JLabel(label), confirmConstraints(0, row))
        panel.add(valueLabel, confirmConstraints(1, row, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))
    }
}

private data class InputValidationError(
    val name: String,
    val message: String,
)

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

private fun formatHistoryStatus(entry: DeployHistoryEntry): String {
    return entry.conclusion
        ?.takeIf { it.isNotBlank() }
        ?: entry.status?.takeIf { it.isNotBlank() }
        ?: entry.runId?.let { "triggered" }
        ?: "not tracked"
}

private fun formatHistoryStatusTooltip(entry: DeployHistoryEntry): String {
    return listOf(
        entry.runId?.let { "Run #$it" }.orEmpty(),
        "Status: ${formatHistoryStatus(entry)}",
        entry.runUrl?.takeIf { it.isNotBlank() }?.let { "URL: $it" }.orEmpty(),
        entry.statusUpdatedAt.takeIf { it > 0 }?.let { "Updated: ${formatHistoryTime(it)}" }.orEmpty(),
    )
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun formatConfirmInputs(inputs: Map<String, String>): String {
    return inputs
        .filterValues { it.isNotBlank() }
        .entries
        .joinToString("\n") { (key, value) -> "$key=$value" }
        .ifBlank { "No inputs" }
}

private fun confirmConstraints(
    x: Int,
    y: Int,
    fill: Int = GridBagConstraints.NONE,
    weightX: Double = 0.0,
    weightY: Double = 0.0,
    anchor: Int = GridBagConstraints.WEST,
): GridBagConstraints {
    return GridBagConstraints().apply {
        gridx = x
        gridy = y
        this.fill = fill
        this.weightx = weightX
        this.weighty = weightY
        insets = Insets(6, 6, 6, 6)
        this.anchor = anchor
    }
}

private fun copyText(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
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
