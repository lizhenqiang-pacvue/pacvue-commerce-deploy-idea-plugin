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
        )
        val next = sequenceOf(entry)
            .plus(getRecentDeploys(repoId).asSequence().filter { signature(it) != signature(entry) })
            .take(HISTORY_LIMIT)
            .toList()

        writeEntries(historyKey(repoId), next)
        return next
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

    private fun signature(entry: DeployHistoryEntry): DeployHistorySignature {
        return DeployHistorySignature(
            repoId = entry.repoId,
            branch = entry.branch,
            workflow = entry.workflow,
            inputs = entry.inputs,
        )
    }

    private data class DeployHistorySignature(
        val repoId: String,
        val branch: String,
        val workflow: String,
        val inputs: Map<String, String>,
    )

    companion object {
        private const val LEGACY_HISTORY_KEY = "pacvueDeploy.deployHistory"
        private const val HISTORY_LIMIT = 10
    }
}
