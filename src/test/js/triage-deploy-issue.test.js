const assert = require("node:assert/strict")
const test = require("node:test")

const { buildTriageResult, extractPayload } = require("../../../scripts/triage-deploy-issue.js")

test("extracts structured payload from auto-triage issue body", () => {
  const payload = extractPayload(`
## Payload

\`\`\`json
{"reporter":"pacvue-commerce-deploy-idea-plugin","failureCategory":"script_parse_failed","triageRoute":"plugin_code","dedupeFingerprint":"abc123"}
\`\`\`
`)

  assert.equal(payload.failureCategory, "script_parse_failed")
  assert.equal(payload.triageRoute, "plugin_code")
  assert.equal(payload.dedupeFingerprint, "abc123")
})

test("routes plugin code issues to plugin fix dispatch", () => {
  const result = buildTriageResult({
    number: 12,
    title: "[auto-triage][script_parse_failed] Deploy trigger failed",
    html_url: "https://github.com/example/repo/issues/12",
    labels: [{ name: "auto-triage" }],
    body: `
## Payload

\`\`\`json
{"reporter":"pacvue-commerce-deploy-idea-plugin","failureCategory":"script_parse_failed","triageRoute":"plugin_code","dedupeFingerprint":"abc123","diagnosisSummary":"Parser failed","recommendedAction":"Fix parser"}
\`\`\`
`
  })

  assert.equal(result.shouldProcess, true)
  assert.equal(result.action, "request_plugin_source_fix")
  assert.equal(result.shouldDispatchPluginFix, true)
  assert.equal(result.shouldSendEmail, false)
  assert.ok(result.labels.includes("needs-plugin-fix"))
})

test("routes project config issues to email notification", () => {
  const result = buildTriageResult({
    number: 13,
    title: "[auto-triage][invalid_project_config] Deploy trigger failed",
    html_url: "https://github.com/example/repo/issues/13",
    labels: [{ name: "auto-triage" }],
    body: `
## Payload

\`\`\`json
{"reporter":"pacvue-commerce-deploy-idea-plugin","failureCategory":"invalid_project_config","triageRoute":"project_config","commerceRepo":"commerce/project","dedupeFingerprint":"def456"}
\`\`\`
`
  })

  assert.equal(result.shouldProcess, true)
  assert.equal(result.action, "notify_project_owner")
  assert.equal(result.shouldSendEmail, true)
  assert.equal(result.shouldDispatchPluginFix, false)
  assert.ok(result.labels.includes("notify/project-owner"))
})

test("ignores non auto-triage issues", () => {
  const result = buildTriageResult({
    number: 14,
    title: "Normal issue",
    body: "Nothing to route",
    labels: []
  })

  assert.equal(result.shouldProcess, false)
})
