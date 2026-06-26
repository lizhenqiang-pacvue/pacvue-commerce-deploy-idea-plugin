package com.pacvue.deploy

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

internal data class DeployHistoryEntry(
    val id: String = "",
    val ts: Long = 0,
    val repoId: String = "",
    val branch: String = "",
    val workflow: String = "",
    val workflowName: String = "",
    val inputs: Map<String, String> = emptyMap(),
    val runId: Long? = null,
    val runUrl: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    val statusUpdatedAt: Long = 0,
)

internal class DeployHistoryCache(project: Project) {
    private val properties = PropertiesComponent.getInstance(project)
    private val gson = Gson()

    fun getRecentDeploys(repoId: String): List<DeployHistoryEntry> {
        if (repoId.isBlank()) return emptyList()

        val scopedEntries = readEntries(historyKey(repoId))
        if (scopedEntries.isNotEmpty()) {
            return scopedEntries
        }

        return readEntries(LEGACY_HISTORY_KEY).filter { it.repoId == repoId }
    }

    fun findRecentDeploy(repoId: String, branch: String, workflow: String): DeployHistoryEntry? {
        if (repoId.isBlank() || branch.isBlank() || workflow.isBlank()) return null
        return getRecentDeploys(repoId).firstOrNull {
            it.branch == branch && it.workflow == workflow
        }
    }

    fun append(
        repoId: String,
        branch: String,
        workflow: String,
        workflowName: String,
        inputs: Map<String, String>,
        run: WorkflowRunStatus?,
    ): List<DeployHistoryEntry> {
        if (repoId.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        val entry = DeployHistoryEntry(
            id = now.toString(),
            ts = now,
            repoId = repoId,
            branch = branch,
            workflow = workflow,
            workflowName = workflowName,
            inputs = inputs,
            runId = run?.databaseId,
            runUrl = run?.url,
            status = run?.status,
            conclusion = run?.conclusion?.takeIf { it.isNotBlank() },
            statusUpdatedAt = now,
        )
        val next = sequenceOf(entry)
            .plus(getRecentDeploys(repoId).asSequence())
            .take(HISTORY_LIMIT)
            .toList()

        writeEntries(historyKey(repoId), next)
        return next
    }

    fun updateRunStatus(repoId: String, run: WorkflowRunStatus): List<DeployHistoryEntry> {
        val runId = run.databaseId ?: return getRecentDeploys(repoId)
        if (repoId.isBlank()) return emptyList()

        val scopedKey = historyKey(repoId)
        val scopedEntries = readEntries(scopedKey)
        if (scopedEntries.isNotEmpty()) {
            val scopedNext = updateEntries(scopedEntries, runId, run)
            writeEntries(scopedKey, scopedNext)
            return scopedNext
        }

        val legacyEntries = readEntries(LEGACY_HISTORY_KEY)
        if (legacyEntries.any { it.repoId == repoId && it.runId == runId }) {
            writeEntries(
                LEGACY_HISTORY_KEY,
                legacyEntries.map { entry -> if (entry.repoId == repoId && entry.runId == runId) entry.withRunStatus(run) else entry },
            )
        }
        return getRecentDeploys(repoId)
    }

    fun remove(repoId: String, entryId: String): List<DeployHistoryEntry> {
        if (repoId.isBlank() || entryId.isBlank()) return getRecentDeploys(repoId)

        val scopedKey = historyKey(repoId)
        val scopedEntries = readEntries(scopedKey)
        val scopedNext = scopedEntries.filter { it.id != entryId }
        if (scopedEntries.isNotEmpty()) {
            writeEntries(scopedKey, scopedNext)
        }

        val legacyEntries = readEntries(LEGACY_HISTORY_KEY)
        if (legacyEntries.any { it.repoId == repoId && it.id == entryId }) {
            writeEntries(
                LEGACY_HISTORY_KEY,
                legacyEntries.filter { it.repoId != repoId || it.id != entryId },
            )
        }

        return if (scopedEntries.isNotEmpty()) scopedNext else getRecentDeploys(repoId)
    }

    fun clear(repoId: String) {
        if (repoId.isBlank()) return

        properties.unsetValue(historyKey(repoId))

        val legacyEntries = readEntries(LEGACY_HISTORY_KEY)
        if (legacyEntries.any { it.repoId == repoId }) {
            writeEntries(LEGACY_HISTORY_KEY, legacyEntries.filter { it.repoId != repoId })
        }
    }

    private fun readEntries(key: String): List<DeployHistoryEntry> {
        val raw = properties.getValue(key)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            gson.fromJson(raw, Array<DeployHistoryEntry>::class.java)?.toList().orEmpty()
        } catch (_: JsonSyntaxException) {
            emptyList()
        }
    }

    private fun writeEntries(key: String, entries: List<DeployHistoryEntry>) {
        if (entries.isEmpty()) {
            properties.unsetValue(key)
            return
        }
        properties.setValue(key, gson.toJson(entries))
    }

    private fun historyKey(repoId: String): String {
        return "$LEGACY_HISTORY_KEY::$repoId"
    }

    private fun updateEntries(entries: List<DeployHistoryEntry>, runId: Long, run: WorkflowRunStatus): List<DeployHistoryEntry> {
        return entries.map { entry ->
            if (entry.runId == runId) entry.withRunStatus(run) else entry
        }
    }

    private fun DeployHistoryEntry.withRunStatus(run: WorkflowRunStatus): DeployHistoryEntry {
        return copy(
            runUrl = run.url?.takeIf { it.isNotBlank() } ?: runUrl,
            status = run.status ?: status,
            conclusion = run.conclusion?.takeIf { it.isNotBlank() } ?: conclusion,
            statusUpdatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val LEGACY_HISTORY_KEY = "pacvueDeploy.deployHistory"
        private const val HISTORY_LIMIT = 10
    }
}
