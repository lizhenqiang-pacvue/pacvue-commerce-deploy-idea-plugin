#!/usr/bin/env node

const fs = require("fs")
const path = require("path")

const DEFAULT_LABELS = ["auto-triage", "deploy-failure"]

function parseArgs(argv) {
  const args = {}
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === "--event") args.event = argv[++index]
    else if (arg === "--out") args.out = argv[++index]
    else if (arg === "--github-output") args.githubOutput = argv[++index]
    else if (arg === "--help") args.help = true
  }
  return args
}

function loadIssueFromEvent(eventPath) {
  const event = JSON.parse(fs.readFileSync(eventPath, "utf8"))
  return event.issue || null
}

function isAutoTriageIssue(issue) {
  if (!issue) return false
  const title = String(issue.title || "")
  const body = String(issue.body || "")
  const labels = Array.isArray(issue.labels) ? issue.labels.map((label) => label.name || label) : []

  return title.includes("[auto-triage]") ||
    body.includes("pacvue-commerce-deploy-idea-plugin") ||
    labels.includes("auto-triage")
}

function extractPayload(body) {
  for (const block of extractJsonCodeBlocks(body)) {
    try {
      const parsed = JSON.parse(block)
      if (
        parsed &&
        typeof parsed === "object" &&
        (parsed.reporter === "pacvue-commerce-deploy-idea-plugin" || parsed.failureCategory || parsed.triageRoute)
      ) {
        return parsed
      }
    } catch (_error) {
      // Keep scanning other json blocks.
    }
  }
  return {}
}

function extractJsonCodeBlocks(body) {
  const blocks = []
  const regex = /```json\s*([\s\S]*?)```/gi
  let match = regex.exec(body)
  while (match) {
    blocks.push(match[1].trim())
    match = regex.exec(body)
  }
  return blocks
}

function extractDiagnosisTable(body) {
  const table = {}
  for (const rawLine of String(body || "").split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line.startsWith("|") || !line.endsWith("|")) continue
    const cells = line
      .slice(1, -1)
      .split("|")
      .map((cell) => cell.trim().replace(/^`|`$/g, ""))
    if (cells.length < 2 || cells[0] === "---") continue
    table[cells[0].toLowerCase()] = cells[1]
  }
  return table
}

function extractFingerprint(body, payload, table) {
  if (payload.dedupeFingerprint) return String(payload.dedupeFingerprint)

  const commentMatch = String(body || "").match(/pacvue-deploy-fingerprint:\s*([a-f0-9]{16,64})/i)
  if (commentMatch) return commentMatch[1]

  return table["dedupe fingerprint"] || ""
}

function routeFromCategory(category) {
  switch (category) {
    case "script_parse_failed":
      return "plugin_code"
    case "workflow_not_found":
    case "invalid_project_config":
      return "project_config"
    case "workflow_failed":
      return "workflow_runtime"
    case "github_cli_unavailable":
      return "github_cli"
    case "github_auth_failed":
      return "github_auth"
    case "invalid_inputs":
      return "deploy_input"
    case "dispatch_failed":
      return "github_dispatch"
    default:
      return "manual_triage"
  }
}

function routingFor(route) {
  switch (route) {
    case "plugin_code":
      return {
        action: "request_plugin_source_fix",
        labels: ["triage/plugin-code", "needs-plugin-fix"],
        shouldSendEmail: false,
        shouldDispatchPluginFix: true,
        nextStep: "Create a plugin source fix task from the captured payload. The workflow also emits a repository_dispatch event for a downstream code-fix agent."
      }
    case "project_config":
      return {
        action: "notify_project_owner",
        labels: ["triage/project-config", "notify/project-owner"],
        shouldSendEmail: true,
        shouldDispatchPluginFix: false,
        nextStep: "Notify the Commerce project owner to fix branch, workflow, or .github configuration."
      }
    case "workflow_runtime":
      return {
        action: "notify_workflow_owner",
        labels: ["triage/workflow-runtime", "notify/project-owner"],
        shouldSendEmail: true,
        shouldDispatchPluginFix: false,
        nextStep: "Notify the workflow or project owner to inspect the failed GitHub Actions jobs."
      }
    case "github_auth":
      return {
        action: "request_user_auth_fix",
        labels: ["triage/github-auth", "needs-user-action"],
        shouldSendEmail: false,
        shouldDispatchPluginFix: false,
        nextStep: "Ask the deploy user to run gh auth status / gh auth login and confirm permissions."
      }
    case "github_cli":
      return {
        action: "request_user_cli_fix",
        labels: ["triage/github-cli", "needs-user-action"],
        shouldSendEmail: false,
        shouldDispatchPluginFix: false,
        nextStep: "Ask the deploy user to install GitHub CLI and restart the IDE."
      }
    case "deploy_input":
      return {
        action: "request_input_fix",
        labels: ["triage/deploy-input", "needs-user-action"],
        shouldSendEmail: false,
        shouldDispatchPluginFix: false,
        nextStep: "Ask the deploy user to fix required workflow inputs before running again."
      }
    case "github_dispatch":
      return {
        action: "inspect_dispatch",
        labels: ["triage/github-dispatch", "needs-investigation"],
        shouldSendEmail: false,
        shouldDispatchPluginFix: false,
        nextStep: "Inspect dispatch permissions, workflow ref, branch filters, and delayed run creation."
      }
    default:
      return {
        action: "manual_triage",
        labels: ["triage/manual", "needs-investigation"],
        shouldSendEmail: false,
        shouldDispatchPluginFix: false,
        nextStep: "Review the captured payload and decide whether this is plugin code, project config, or runtime behavior."
      }
  }
}

function buildTriageResult(issue) {
  if (!isAutoTriageIssue(issue)) {
    return { shouldProcess: false, reason: "Issue is not a Pacvue Deploy auto-triage issue." }
  }

  const body = String(issue.body || "")
  const payload = extractPayload(body)
  const table = extractDiagnosisTable(body)
  const failureCategory = payload.failureCategory || table["failure category"] || "unknown"
  const triageRoute = payload.triageRoute || table["triage route"] || routeFromCategory(failureCategory)
  const routing = routingFor(triageRoute)
  const fingerprint = extractFingerprint(body, payload, table)
  const labels = unique([
    ...DEFAULT_LABELS,
    failureCategory,
    ...routing.labels
  ])
  const issueUrl = issue.html_url || issue.url || ""
  const issueNumber = issue.number || null
  const summary = payload.diagnosisSummary || table.summary || payload.errorMessage || "No diagnosis summary was captured."
  const recommendedAction = payload.recommendedAction || table["recommended action"] || routing.nextStep

  return {
    shouldProcess: true,
    issueNumber,
    issueUrl,
    issueTitle: issue.title || "",
    failureCategory,
    triageRoute,
    action: routing.action,
    labels,
    shouldSendEmail: routing.shouldSendEmail,
    shouldDispatchPluginFix: routing.shouldDispatchPluginFix,
    fingerprint,
    payload,
    summary,
    recommendedAction,
    nextStep: routing.nextStep,
    commentBody: buildCommentBody({
      failureCategory,
      triageRoute,
      action: routing.action,
      labels,
      fingerprint,
      summary,
      recommendedAction,
      nextStep: routing.nextStep
    }),
    emailSubject: `[Pacvue Deploy][${failureCategory}] ${issue.title || "Deploy failure"}`,
    emailBody: buildEmailBody({
      issueTitle: issue.title || "",
      issueUrl,
      failureCategory,
      triageRoute,
      summary,
      recommendedAction,
      payload
    }),
    dispatchPayload: {
      event_type: "pacvue-deploy-plugin-fix-request",
      client_payload: {
        issueNumber,
        issueUrl,
        failureCategory,
        triageRoute,
        fingerprint,
        summary,
        recommendedAction,
        payload
      }
    }
  }
}

function buildCommentBody(result) {
  return [
    "## Auto triage result",
    "",
    "| Field | Value |",
    "| --- | --- |",
    `| Failure category | \`${escapeTable(result.failureCategory)}\` |`,
    `| Triage route | \`${escapeTable(result.triageRoute)}\` |`,
    `| Action | \`${escapeTable(result.action)}\` |`,
    `| Dedupe fingerprint | \`${escapeTable(result.fingerprint || "n/a")}\` |`,
    `| Labels | \`${escapeTable(result.labels.join(", "))}\` |`,
    "",
    "## Summary",
    "",
    result.summary,
    "",
    "## Recommended action",
    "",
    result.recommendedAction,
    "",
    "## Automation next step",
    "",
    result.nextStep
  ].join("\n")
}

function buildEmailBody(result) {
  return [
    "Pacvue Deploy detected a project/workflow issue that needs owner action.",
    "",
    `Issue: ${result.issueTitle}`,
    `Issue URL: ${result.issueUrl || "n/a"}`,
    `Failure category: ${result.failureCategory}`,
    `Triage route: ${result.triageRoute}`,
    "",
    "Summary:",
    result.summary,
    "",
    "Recommended action:",
    result.recommendedAction,
    "",
    "Payload:",
    JSON.stringify(result.payload || {}, null, 2)
  ].join("\n")
}

function escapeTable(value) {
  return String(value || "").replace(/\|/g, "\\|")
}

function unique(values) {
  return values
    .map(String)
    .map((value) => value.trim())
    .filter(Boolean)
    .filter((value, index, array) => array.indexOf(value) === index)
}

function writeGithubOutput(filePath, outputs) {
  if (!filePath) return
  const lines = []
  for (const [key, value] of Object.entries(outputs)) {
    lines.push(`${key}=${String(value).replace(/\r?\n/g, " ")}`)
  }
  fs.appendFileSync(filePath, `${lines.join("\n")}\n`)
}

function writeOutputFiles(outDir, result) {
  fs.mkdirSync(outDir, { recursive: true })
  const commentFile = path.join(outDir, "comment.md")
  const emailBodyFile = path.join(outDir, "email.txt")
  const dispatchPayloadFile = path.join(outDir, "dispatch.json")
  const resultFile = path.join(outDir, "result.json")

  fs.writeFileSync(commentFile, result.commentBody || "")
  fs.writeFileSync(emailBodyFile, result.emailBody || "")
  fs.writeFileSync(dispatchPayloadFile, JSON.stringify(result.dispatchPayload || {}, null, 2))
  fs.writeFileSync(resultFile, JSON.stringify(result, null, 2))

  return { commentFile, emailBodyFile, dispatchPayloadFile, resultFile }
}

function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv)
  if (args.help || !args.event) {
    console.log("Usage: node scripts/triage-deploy-issue.js --event <github-event-json> [--out <dir>] [--github-output <file>]")
    return args.help ? 0 : 2
  }

  const issue = loadIssueFromEvent(args.event)
  const result = buildTriageResult(issue)
  const files = args.out ? writeOutputFiles(args.out, result) : {}

  writeGithubOutput(args.githubOutput, {
    should_process: result.shouldProcess ? "true" : "false",
    issue_number: result.issueNumber || "",
    labels: result.labels ? result.labels.join(",") : "",
    failure_category: result.failureCategory || "",
    triage_route: result.triageRoute || "",
    action: result.action || "",
    should_send_email: result.shouldSendEmail ? "true" : "false",
    should_dispatch_plugin_fix: result.shouldDispatchPluginFix ? "true" : "false",
    comment_file: files.commentFile || "",
    email_body_file: files.emailBodyFile || "",
    dispatch_payload_file: files.dispatchPayloadFile || "",
    email_subject: result.emailSubject || ""
  })

  console.log(JSON.stringify(result, null, 2))
  return 0
}

if (require.main === module) {
  process.exitCode = main()
}

module.exports = {
  buildTriageResult,
  extractPayload,
  routeFromCategory,
  routingFor
}
