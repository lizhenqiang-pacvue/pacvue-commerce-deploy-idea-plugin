const { execFileSync, spawnSync } = require("node:child_process")
const fs = require("node:fs")
const path = require("node:path")
const os = require("node:os")

const TEST_WORKFLOW_NAME = "测试环境发版"
const TEST_BRANCH_PATTERN = /^(test|sprint)\//
const MERGE_KEYWORD_PATTERN = /(合并|合到|merge)/
const BRANCH_PATTERN = /\b((?:test|sprint)\/[^\s，,]+)/i
const TOOL_ENV_KEYS = {
  git: "PACVUE_GIT_PATH",
  gh: "PACVUE_GH_PATH"
}

function getWindowsExecutableDirectories() {
  const localAppData = process.env.LOCALAPPDATA
  const programFiles = process.env.ProgramFiles || "C:\\Program Files"
  const programFilesX86 = process.env["ProgramFiles(x86)"] || "C:\\Program Files (x86)"

  return [
    path.join(programFiles, "Git", "cmd"),
    path.join(programFiles, "Git", "bin"),
    path.join(programFilesX86, "Git", "cmd"),
    localAppData ? path.join(localAppData, "Programs", "Git", "cmd") : null,
    localAppData ? path.join(localAppData, "Programs", "Git", "bin") : null,
    path.join(programFiles, "GitHub CLI"),
    localAppData ? path.join(localAppData, "Programs", "GitHub CLI") : null
  ].filter(Boolean)
}

function getPlatformExecutableDirectories() {
  if (process.platform === "win32") {
    return getWindowsExecutableDirectories()
  }

  return [
    "/opt/homebrew/bin",
    "/usr/local/bin",
    "/opt/local/bin",
    "/usr/bin",
    "/bin"
  ]
}

function getExecutableCandidateNames(command) {
  if (process.platform !== "win32") {
    return [command]
  }

  if (/\.(exe|cmd|bat)$/i.test(command)) {
    return [command]
  }

  return [`${command}.exe`, `${command}.cmd`, `${command}.bat`, command]
}

function isExistingExecutable(candidatePath) {
  try {
    if (!fs.existsSync(candidatePath)) return false
    return !fs.statSync(candidatePath).isDirectory()
  } catch (_error) {
    return false
  }
}

function parseBoolean(value) {
  if (value === undefined) return true
  return value === true || value === "true"
}

function parseScalar(rawValue) {
  const value = String(rawValue ?? "").trim()
  if (value === "true") return true
  if (value === "false") return false
  return value.replace(/^["']|["']$/g, "")
}

function getIndent(line) {
  return line.match(/^ */)[0].length
}

function parseWorkflowFile(filePath) {
  const content = fs.readFileSync(filePath, "utf8")
  const lines = content.split(/\r?\n/)
  const workflow = {
    filePath,
    name: "",
    hasWorkflowDispatch: false,
    inputs: {},
    branchInputName: null
  }

  let inInputs = false
  let inputIndent = null
  let currentInputName = null
  let inOptions = false
  let optionIndent = null

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith("#")) continue

    if (!workflow.name && trimmed.startsWith("name:")) {
      workflow.name = parseScalar(trimmed.slice("name:".length))
      continue
    }

    if (/^workflow_dispatch:\s*(?:#.*)?$/.test(trimmed)) {
      workflow.hasWorkflowDispatch = true
      continue
    }

    if (/^inputs:\s*(?:#.*)?$/.test(trimmed) && workflow.hasWorkflowDispatch) {
      inInputs = true
      inputIndent = getIndent(line)
      continue
    }

    if (!inInputs) continue

    const indent = getIndent(line)
    if (indent <= inputIndent && !trimmed.startsWith("- ")) {
      inInputs = false
      currentInputName = null
      inOptions = false
      continue
    }

    const inputMatch = trimmed.match(/^([A-Za-z0-9_-]+):\s*(?:#.*)?$/)
    if (inputMatch && indent === inputIndent + 2) {
      currentInputName = inputMatch[1]
      workflow.inputs[currentInputName] = { required: false, options: [] }
      inOptions = false
      optionIndent = null
      continue
    }

    if (!currentInputName) continue

    if (/^options:\s*(?:#.*)?$/.test(trimmed)) {
      inOptions = true
      optionIndent = indent
      continue
    }

    if (inOptions && trimmed.startsWith("- ")) {
      workflow.inputs[currentInputName].options.push(parseScalar(trimmed.slice(2)))
      continue
    }

    if (inOptions && indent <= optionIndent) {
      inOptions = false
      optionIndent = null
    }

    const propertyMatch = trimmed.match(/^([A-Za-z0-9_-]+):\s*(.*)$/)
    if (propertyMatch) {
      const [, key, value] = propertyMatch
      workflow.inputs[currentInputName][key] = parseScalar(value)
    }
  }

  workflow.branchInputName = findBranchInputName(workflow.inputs)
  return workflow
}

function findBranchInputName(inputs) {
  const exactMatch = ["branch_manually", "branchManual", "branch_manual"].find((name) => inputs[name])
  if (exactMatch) return exactMatch

  return (
    Object.entries(inputs).find(([name, input]) => {
      const normalizedName = name.toLowerCase()
      return normalizedName.includes("branch") && input.type !== "choice"
    })?.[0] ?? null
  )
}

function discoverWorkflows(repoRoot, options = {}) {
  const workflowDir = path.join(repoRoot, ".github", "workflows")
  const localWorkflows = fs.existsSync(workflowDir)
    ? fs
        .readdirSync(workflowDir)
        .filter((file) => file.endsWith(".yml") || file.endsWith(".yaml"))
        .map((file) => parseWorkflowFile(path.join(workflowDir, file)))
        .filter((workflow) => workflow.hasWorkflowDispatch)
        .filter((workflow) => options.includeAll || workflow.name.includes(TEST_WORKFLOW_NAME))
    : []

  if (hasDeployWorkflow(localWorkflows)) return localWorkflows
  if (!options.includeAll && localWorkflows.length > 0) return localWorkflows

  const ghWorkflows = discoverGhWorkflows(repoRoot, options)
  return ghWorkflows.length > 0 ? ghWorkflows : localWorkflows
}

function hasDeployWorkflow(workflows) {
  return workflows.some((workflow) => workflow.name.includes(TEST_WORKFLOW_NAME))
}

function discoverGhWorkflows(repoRoot, options = {}) {
  const ghPath = getCommandPath("gh")
  if (!ghPath) return []

  const result = spawnSync(ghPath, ["workflow", "list", "--json", "name,path,state"], { cwd: repoRoot, encoding: "utf8" })
  if (result.status !== 0) return []

  try {
    const workflows = JSON.parse(result.stdout)
    if (!Array.isArray(workflows)) return []

    return workflows
      .filter((workflow) => !workflow.state || workflow.state === "active")
      .filter((workflow) => options.includeAll || String(workflow.name || "").includes(TEST_WORKFLOW_NAME))
      .map((workflow) => ({
        filePath: path.resolve(repoRoot, workflow.path || workflow.name),
        name: workflow.name || workflow.path || "",
        hasWorkflowDispatch: true,
        inputs: {},
        branchInputName: null
      }))
  } catch (_error) {
    return []
  }
}

function extractExplicitBranch(userText = "") {
  return userText.match(BRANCH_PATTERN)?.[1] ?? null
}

function detectMode({ userText = "", headBranch = "", explicitBranch = null }) {
  const targetBranch = explicitBranch || extractExplicitBranch(userText)
  const hasMergeIntent = MERGE_KEYWORD_PATTERN.test(userText)

  if (hasMergeIntent && targetBranch && headBranch === targetBranch) {
    return { mode: null, targetBranch, needsClarification: true, reason: "HEAD is already on the target test branch; ask which feature branch to merge." }
  }

  if (hasMergeIntent && targetBranch) return { mode: "C", targetBranch, needsClarification: false }
  if (hasMergeIntent && !targetBranch) return { mode: null, targetBranch: null, needsClarification: true, reason: "Merge intent needs a target test branch." }
  if (targetBranch) return { mode: "B", targetBranch, needsClarification: false }
  if (TEST_BRANCH_PATTERN.test(headBranch)) return { mode: "A", targetBranch: headBranch, needsClarification: false }

  return { mode: null, targetBranch: null, needsClarification: true, reason: "Current branch does not look like a test branch; ask whether to deploy current HEAD or merge into a test branch." }
}

function parseInputPairs(inputPairs = []) {
  return inputPairs.reduce((inputs, pair) => {
    const separatorIndex = pair.indexOf("=")
    if (separatorIndex === -1) throw new Error(`Invalid --input value "${pair}". Use key=value.`)
    const key = pair.slice(0, separatorIndex)
    const value = pair.slice(separatorIndex + 1)
    inputs[key] = value
    return inputs
  }, {})
}

function resolveInputs({ workflow, providedInputs = {}, lastRunInputs = {}, targetBranch = "" }) {
  const resolvedInputs = {}
  const missingRequiredInputs = []
  const suggestions = {}

  for (const [name, input] of Object.entries(workflow.inputs)) {
    if (name === workflow.branchInputName) continue

    if (Object.prototype.hasOwnProperty.call(providedInputs, name)) {
      resolvedInputs[name] = providedInputs[name]
      continue
    }

    if (Object.prototype.hasOwnProperty.call(lastRunInputs, name)) {
      resolvedInputs[name] = lastRunInputs[name]
      continue
    }

    if (Object.prototype.hasOwnProperty.call(input, "default")) {
      resolvedInputs[name] = String(input.default)
      continue
    }

    if (input.required) {
      missingRequiredInputs.push(name)
      if (input.options?.length) suggestions[name] = input.options
    }
  }

  if (workflow.branchInputName) {
    resolvedInputs[workflow.branchInputName] = targetBranch
  }

  return { resolvedInputs, missingRequiredInputs, suggestions }
}

function normalizeWorkflowPath(workflowFile) {
  return workflowFile.replace(/\\/g, "/")
}

function getWorkflowName(workflowFile) {
  return normalizeWorkflowPath(workflowFile).split("/").pop()
}

function buildWorkflowCommand({ workflowFile, refBranch, inputs, branchInputName }) {
  const ghPath = getCommandPath("gh") || "gh"
  const command = [ghPath, "workflow", "run", normalizeWorkflowPath(workflowFile), "--ref", refBranch]

  for (const [key, value] of Object.entries(inputs)) {
    if (key === branchInputName) continue
    command.push("-f", `${key}=${value}`)
  }

  if (branchInputName) {
    command.push("-f", `${branchInputName}=${refBranch}`)
  }

  return command
}

function quoteShellArg(value) {
  if (/^[A-Za-z0-9_./:=@+-]+$/.test(value)) return value
  return `'${value.replace(/'/g, "'\\''")}'`
}

function formatCommand(command) {
  return command.map(quoteShellArg).join(" ")
}

function runGit(repoRoot, args, options = {}) {
  const gitPath = getCommandPath("git")
  // 如果找不到 git，直接返回错误
  if (!gitPath) {
    throw new Error(`git 命令未在系统路径中找到。请确保 Git 已安装并添加到系统 PATH 环境变量中。`)
  }
  return execFileSync(gitPath, args, { cwd: repoRoot, encoding: "utf8", stdio: options.stdio ?? ["ignore", "pipe", "pipe"] }).trim()
}

/**
 * 跨平台检测命令是否存在
 * 特别处理 Windows 下的 git 和 gh 命令
 */
function commandExists(command) {
  return Boolean(getCommandPath(command))
}

function resolveConfiguredToolPath(command) {
  const envKey = TOOL_ENV_KEYS[command]
  if (!envKey) return null

  const configuredPath = process.env[envKey]?.trim()
  if (!configuredPath || !isExistingExecutable(configuredPath)) {
    return null
  }

  return configuredPath
}

function searchExecutableInDirectories(command, directories) {
  for (const directory of directories) {
    for (const candidateName of getExecutableCandidateNames(command)) {
      const candidate = path.join(directory, candidateName)
      if (isExistingExecutable(candidate)) {
        return candidate
      }
    }
  }

  return null
}

/**
 * 获取命令的完整路径（跨平台）
 */
function getCommandPath(command) {
  const configuredPath = resolveConfiguredToolPath(command)
  if (configuredPath) {
    return configuredPath
  }

  // 如果命令已经是完整路径
  if (command.includes("/") || command.includes("\\") || command.includes(":")) {
    const candidate = path.isAbsolute(command) ? command : path.resolve(command)
    if (isExistingExecutable(candidate)) {
      return candidate
    }
  }

  // Windows 系统优先处理
  if (process.platform === "win32") {
    try {
      // 直接使用 cmd /c where，这个命令会正确继承当前进程的环境变量
      const whereResult = spawnSync("cmd", ["/c", "where", command], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"]
      })
      if (whereResult.status === 0 && whereResult.stdout?.trim()) {
        const resolvedPath = whereResult.stdout.trim().split(/\r?\n/).shift()?.trim()
        if (resolvedPath && isExistingExecutable(resolvedPath)) {
          return resolvedPath
        }
      }
    } catch (_error) {
      // 忽略错误，尝试其他方法
    }
  } else {
    // Unix/macOS 系统使用 which
    try {
      const whichResult = spawnSync("which", [command], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"]
      })
      if (whichResult.status === 0 && whichResult.stdout?.trim()) {
        const resolvedPath = whichResult.stdout.trim()
        if (isExistingExecutable(resolvedPath)) {
          return resolvedPath
        }
      }
    } catch (_error) {
      // 忽略错误，尝试其他方法
    }
  }

  const pathEnv = process.env.PATH || ""
  const pathSeparator = process.platform === "win32" ? ";" : ":"
  const pathDirs = pathEnv.split(pathSeparator).map((dir) => dir.trim()).filter(Boolean)
  const platformDirs = getPlatformExecutableDirectories()

  return searchExecutableInDirectories(command, [...pathDirs, ...platformDirs])
}

function getMissingGhPayload() {
  return {
    ok: false,
    reason: "未检测到 GitHub CLI（gh），无法触发 GitHub Actions workflow。",
    remediation: [
      "Windows: winget install --id GitHub.cli",
      "macOS: brew install gh",
      "安装后执行: gh auth login",
      "确认登录状态: gh auth status",
      "如果刚安装完仍然报错，请完全退出并重新打开 IntelliJ IDEA，让插件读取新的 PATH。"
    ]
  }
}

function validateRemoteBranch(repoRoot, branch) {
  const gitPath = getCommandPath("git")
  if (!gitPath) {
    throw new Error(`git 命令未找到，无法验证远程分支`)
  }
  const result = spawnSync(gitPath, ["ls-remote", "--exit-code", "--heads", "origin", branch], { cwd: repoRoot, encoding: "utf8" })
  return result.status === 0
}

function getLastSuccessfulRunInputs({ workflowFile, targetBranch }) {
  const ghPath = getCommandPath("gh")
  if (!ghPath || !commandExists("gh")) return {}

  const workflowName = getWorkflowName(workflowFile)
  const result = spawnSync(ghPath, ["run", "list", "--workflow", workflowName, "--status=success", "--limit", "20", "--json", "displayTitle"], { encoding: "utf8" })
  if (result.status !== 0) return {}

  try {
    const runs = JSON.parse(result.stdout)
    const matchingRun = runs.find((run) => run.displayTitle?.includes(`分支:${targetBranch}`)) || runs[0]
    return parseDisplayTitle(matchingRun?.displayTitle || "")
  } catch (_error) {
    return {}
  }
}

function waitFor(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds)
}

function parseWorkflowRuns(stdout) {
  try {
    const runs = JSON.parse(stdout)
    return Array.isArray(runs) ? runs : []
  } catch (_error) {
    return []
  }
}

function findNewWorkflowRun(runs, dispatchStartedAt) {
  const earliestCreatedAt = dispatchStartedAt.getTime() - 5000
  return runs.find((run) => {
    const createdAt = new Date(run.createdAt || 0).getTime()
    return Number.isFinite(createdAt) && createdAt >= earliestCreatedAt
  })
}

function runWorkflowDispatch({
  command,
  repoRoot,
  workflowFile,
  targetBranch,
  spawn = spawnSync,
  sleep = waitFor,
  maxAttempts = 6,
  delayMs = 2000
}) {
  const dispatchStartedAt = new Date()
  const dispatchResult = spawn(command[0], command.slice(1), { cwd: repoRoot, encoding: "utf8" })
  const stdout = dispatchResult.stdout || ""
  const stderr = dispatchResult.stderr || ""

  if (dispatchResult.status !== 0) {
    return { status: dispatchResult.status || 1, stdout, stderr, verified: false }
  }

  const workflowName = getWorkflowName(workflowFile)
  const listArgs = [
    "run",
    "list",
    "--workflow",
    workflowName,
    "--branch",
    targetBranch,
    "--event",
    "workflow_dispatch",
    "--limit",
    "10",
    "--json",
    "databaseId,status,conclusion,displayTitle,createdAt,url"
  ]

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const ghPath = getCommandPath("gh") || "gh"
    const listResult = spawn(ghPath, listArgs, { cwd: repoRoot, encoding: "utf8" })
    if (listResult.status === 0) {
      const run = findNewWorkflowRun(parseWorkflowRuns(listResult.stdout), dispatchStartedAt)
      if (run) {
        return {
          status: 0,
          stdout,
          stderr,
          verified: true,
          run
        }
      }
    }

    if (attempt < maxAttempts) sleep(delayMs)
  }

  return {
    status: 1,
    stdout,
    stderr: [
      stderr,
      `No GitHub Actions run was found for ${workflowName} on ${targetBranch} after gh workflow run returned successfully.`
    ]
      .filter(Boolean)
      .join("\n"),
    verified: false
  }
}

function parseDisplayTitle(displayTitle) {
  const parsed = {}
  for (const part of displayTitle.split(",")) {
    const [rawKey, ...rawValue] = part.split(":")
    const key = rawKey?.trim()
    const value = rawValue.join(":").trim()
    if (!key || !value) continue

    if (key === "项目名称") parsed.ProjectName = value
    if (key === "环境") parsed.environment = value
    if (key === "分支") parsed.branch = value
  }
  return parsed
}

function parseArgs(argv) {
  const args = {
    repoRoot: process.cwd(),
    inputPairs: [],
    dryRun: true,
    dispatch: false,
    skipRemoteCheck: false,
    skipLastRunInputs: false,
    listWorkflowsJson: false
  }

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === "--repo-root") args.repoRoot = path.resolve(argv[++index])
    else if (arg === "--branch") args.branch = argv[++index]
    else if (arg === "--message") args.message = argv[++index]
    else if (arg === "--workflow") args.workflow = argv[++index]
    else if (arg === "--list-workflows-json") args.listWorkflowsJson = true
    else if (arg === "--input") args.inputPairs.push(argv[++index])
    else if (arg === "--dry-run") args.dryRun = parseBoolean(argv[index + 1]?.startsWith("--") ? undefined : argv[++index])
    else if (arg === "--dispatch") {
      args.dispatch = true
      args.dryRun = false
    } else if (arg === "--skip-remote-check") args.skipRemoteCheck = true
    else if (arg === "--skip-last-run-inputs" || arg === "--no-last-run-inputs") args.skipLastRunInputs = true
    else if (arg === "--help" || arg === "-h") args.help = true
    else throw new Error(`Unknown argument: ${arg}`)
  }

  return args
}

function printHelp() {
  console.log(`Usage:
  node scripts/deploy-to-test.js [options]

Options:
  --repo-root <path>        Git repo root. Defaults to current working directory.
  --branch <branch>         Target test branch.
  --message <text>          User request text used for mode detection.
  --workflow <file>         Workflow filename or path when multiple workflows match.
  --list-workflows-json     Print matching local workflow metadata as JSON.
  --input key=value         Workflow input. Repeat for multiple inputs.
  --dry-run                 Print plan and command without dispatching. Default.
  --dispatch                Execute gh workflow run after validation.
  --skip-remote-check       Skip origin branch existence check.
  --skip-last-run-inputs    Skip gh run list lookup for previous workflow inputs.
`)
}

function printJson(data) {
  console.log(JSON.stringify(data, null, 2))
}

function selectWorkflow(workflows, requestedWorkflow) {
  if (requestedWorkflow) {
    const normalized = requestedWorkflow.replace(/\\/g, "/")
    const workflow = workflows.find((item) => item.filePath.replace(/\\/g, "/").endsWith(normalized) || path.basename(item.filePath) === normalized)
    if (!workflow) throw new Error(`Workflow "${requestedWorkflow}" was not found among workflow_dispatch workflows.`)
    return workflow
  }

  if (workflows.length === 0) throw new Error(`No workflow with name containing "${TEST_WORKFLOW_NAME}" was found.`)
  if (workflows.length > 1) {
    const names = workflows.map((workflow) => `${workflow.filePath} (${workflow.name})`).join("\n")
    throw new Error(`Multiple matching workflows found. Re-run with --workflow.\n${names}`)
  }

  return workflows[0]
}

function formatWorkflowMetadata(repoRoot, workflow) {
  return {
    file: normalizeWorkflowPath(path.relative(repoRoot, workflow.filePath)),
    name: workflow.name,
    hasWorkflowDispatch: workflow.hasWorkflowDispatch,
    isDefaultDeployWorkflow: workflow.name.includes(TEST_WORKFLOW_NAME),
    branchInputName: workflow.branchInputName,
    inputs: workflow.inputs
  }
}

function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv)
  if (args.help) {
    printHelp()
    return 0
  }

  const repoRoot = args.repoRoot
  if (args.listWorkflowsJson) {
    const workflows = discoverWorkflows(repoRoot, { includeAll: true }).map((workflow) => formatWorkflowMetadata(repoRoot, workflow))
    printJson({ ok: workflows.length > 0, repoRoot, workflows })
    return workflows.length > 0 ? 0 : 2
  }

  if (!commandExists("git")) throw new Error("git is required.")
  if (args.dispatch && !commandExists("gh")) {
    printJson(getMissingGhPayload())
    return 2
  }

  const headBranch = runGit(repoRoot, ["branch", "--show-current"])
  const explicitBranch = args.branch || extractExplicitBranch(args.message || "")
  const modeInfo = detectMode({ userText: args.message || "", headBranch, explicitBranch })
  const targetBranch = explicitBranch || modeInfo.targetBranch

  if (modeInfo.needsClarification || !targetBranch) {
    printJson({ ok: false, needsClarification: true, mode: modeInfo.mode, reason: modeInfo.reason, headBranch })
    return 2
  }

  if (!args.skipRemoteCheck && !validateRemoteBranch(repoRoot, targetBranch)) {
    printJson({ ok: false, reason: `Remote branch "${targetBranch}" was not found on origin.`, targetBranch })
    return 2
  }

  const workflows = discoverWorkflows(repoRoot, { includeAll: Boolean(args.workflow) })
  const workflow = selectWorkflow(workflows, args.workflow)
  if (!workflow.hasWorkflowDispatch) throw new Error(`Workflow "${workflow.filePath}" does not use workflow_dispatch.`)

  const workflowFile = normalizeWorkflowPath(path.relative(repoRoot, workflow.filePath))
  const providedInputs = parseInputPairs(args.inputPairs)
  const lastRunInputs = args.skipLastRunInputs ? {} : getLastSuccessfulRunInputs({ workflowFile, targetBranch })
  const inputResolution = resolveInputs({ workflow, providedInputs, lastRunInputs, targetBranch })
  const command = buildWorkflowCommand({
    workflowFile,
    refBranch: targetBranch,
    inputs: inputResolution.resolvedInputs,
    branchInputName: workflow.branchInputName
  })

  const output = {
    ok: inputResolution.missingRequiredInputs.length === 0,
    mode: modeInfo.mode,
    workflow: {
      file: workflowFile,
      name: workflow.name,
      branchInputName: workflow.branchInputName,
      branchStrategy: workflow.branchInputName ? "input-and-ref" : "ref-only"
    },
    headBranch,
    targetBranch,
    resolvedInputs: inputResolution.resolvedInputs,
    missingRequiredInputs: inputResolution.missingRequiredInputs,
    suggestions: inputResolution.suggestions,
    command,
    commandPreview: formatCommand(command),
    dryRun: args.dryRun
  }

  if (inputResolution.missingRequiredInputs.length > 0) {
    printJson(output)
    return args.dispatch ? 2 : 0
  }

  if (args.dispatch) {
    const result = runWorkflowDispatch({ command, repoRoot, workflowFile, targetBranch })
    output.ok = result.status === 0
    output.dispatch = {
      verified: result.verified,
      run: result.run || null,
      stdout: result.stdout,
      stderr: result.stderr
    }
    printJson(output)
    if (result.status !== 0) return result.status || 1
  } else {
    printJson(output)
  }

  return 0
}

if (require.main === module) {
  try {
    process.exitCode = main()
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}

module.exports = {
  buildWorkflowCommand,
  commandExists,
  detectMode,
  discoverWorkflows,
  extractExplicitBranch,
  formatWorkflowMetadata,
  getCommandPath,
  parseDisplayTitle,
  parseWorkflowFile,
  runWorkflowDispatch,
  resolveInputs
}
