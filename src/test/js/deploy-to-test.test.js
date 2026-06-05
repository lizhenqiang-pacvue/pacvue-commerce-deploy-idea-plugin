const test = require("node:test")
const assert = require("node:assert/strict")

const { buildWorkflowCommand, commandExists, getCommandPath, runWorkflowDispatch } = require("../../main/resources/scripts/deploy-to-test.js")

test("normalizes workflow paths before passing them to gh", () => {
  const command = buildWorkflowCommand({
    workflowFile: ".github\\workflows\\main.yml",
    refBranch: "sprint/bus/2026Q2_bus_sprint3_test",
    inputs: {
      module: "commerce"
    },
    branchInputName: null
  })

  assert.equal(command[3], ".github/workflows/main.yml")
})

test("reports an unverified dispatch when gh exits successfully but no run is found", () => {
  const calls = []
  const result = runWorkflowDispatch({
    command: ["gh", "workflow", "run", ".github/workflows/us-test.yml", "--ref", "test/sprint/demo"],
    repoRoot: "/repo",
    workflowFile: ".github/workflows/us-test.yml",
    targetBranch: "test/sprint/demo",
    maxAttempts: 1,
    spawn: (command, args) => {
      calls.push([command, args])
      if (args[0] === "workflow") return { status: 0, stdout: "", stderr: "" }
      return { status: 0, stdout: "[]", stderr: "" }
    }
  })

  assert.equal(result.status, 1)
  assert.equal(result.verified, false)
  assert.match(result.stderr, /No GitHub Actions run was found/)
  assert.equal(calls[0][1][0], "workflow")
  assert.deepEqual(calls[1][1].slice(0, 4), ["run", "list", "--workflow", "us-test.yml"])
})

test("returns the new workflow run when dispatch is verified", () => {
  const result = runWorkflowDispatch({
    command: ["gh", "workflow", "run", ".github/workflows/us-test.yml", "--ref", "test/sprint/demo"],
    repoRoot: "/repo",
    workflowFile: ".github/workflows/us-test.yml",
    targetBranch: "test/sprint/demo",
    maxAttempts: 1,
    spawn: (_command, args) => {
      if (args[0] === "workflow") return { status: 0, stdout: "", stderr: "" }
      return {
        status: 0,
        stdout: JSON.stringify([
          {
            databaseId: 123,
            status: "queued",
            conclusion: "",
            displayTitle: "项目名称:demo",
            createdAt: new Date().toISOString(),
            url: "https://github.com/example/repo/actions/runs/123"
          }
        ]),
        stderr: ""
      }
    }
  })

  assert.equal(result.status, 0)
  assert.equal(result.verified, true)
  assert.equal(result.run.databaseId, 123)
  assert.equal(result.run.url, "https://github.com/example/repo/actions/runs/123")
})

test("prefers PACVUE_GIT_PATH when resolving git", () => {
  const previousValue = process.env.PACVUE_GIT_PATH
  process.env.PACVUE_GIT_PATH = process.execPath

  try {
    assert.equal(getCommandPath("git"), process.execPath)
    assert.equal(commandExists("git"), true)
  } finally {
    if (previousValue === undefined) {
      delete process.env.PACVUE_GIT_PATH
    } else {
      process.env.PACVUE_GIT_PATH = previousValue
    }
  }
})
