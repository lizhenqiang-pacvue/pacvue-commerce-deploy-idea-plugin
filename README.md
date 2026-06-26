# Pacvue Commerce Deploy IDEA Plugin

在 JetBrains IDE（IntelliJ IDEA、WebStorm 等）右侧提供 **Pacvue Deploy** 工具窗口，用于选择测试分支、workflow 与输入参数，触发 Pacvue Commerce 测试环境 GitHub Actions 发版，并轮询运行状态。

与 [pacvue-commerce-deploy-extension](https://github.com/pacvue/pacvue-commerce-deploy-extension)（Cursor / VS Code 版）共用同一套 `deploy-to-test.js` 能力，入口独立。

## 功能

- 读取当前项目 Git 分支，下拉选择目标测试分支
- 扫描 `.github/workflows` 下支持 `workflow_dispatch` 的 workflow（默认匹配名称含「测试环境发版」）
- 打开面板或点击 Refresh 时自动执行环境自检，检查 Git 仓库、`.github/workflows`、Node.js、GitHub CLI 与 `gh auth` 状态
- 根据 workflow inputs 动态生成表单
- Run 前校验必填 inputs、choice / boolean 类型值，避免明显错误参数进入发版确认
- Recent Deploys 按当前 Commerce 项目独立保存，支持切换分支 / workflow 时自动回填缓存参数
- Recent Deploys 支持展开 / 收起，每条记录显示独立 run 状态，并支持 **Reuse** 复用历史参数与 **Open Run** 打开对应 GitHub Actions run；`in_progress` 记录显示 **Cancel** 且隐藏 **Clear**，其他记录支持 **Clear** 删除；列表最多展示 5 条，超出后内部滚动
- 点击 **Run** 后先确认 branch、workflow、inputs，再点击 **Confirm Deploy** 触发发版
- 约每 5 秒轮询 GitHub Actions 状态；run 创建后表单与 **Run** 会恢复可用，可继续切换分支 / 配置发起新的发版，面板会转为跟踪最新 run
- 每条 Recent Deploys 记录可直接打开对应 GitHub Actions run，运行中的记录可直接取消
- GitHub Actions 发版成功后自动弹出 IDE 通知
- 发版触发失败或 GitHub Actions run 失败（非 cancelled）时自动创建 `[auto-triage]` GitHub Issue，并写入结构化诊断分类、分流路径、建议动作和机器可读 Payload，便于后续自动排查和分流

## 环境要求

### IDE

| 项目 | 要求 |
|------|------|
| 兼容 IDE | IntelliJ IDEA、WebStorm 等基于 IntelliJ Platform 的 IDE |
| 最低版本 | **2024.2**（build `242`）及以上 |
| 可选依赖 | Git4Idea（已安装 Git 插件时可自动解析 Git 路径） |

### 系统工具

| 工具 | 用途 | 说明 |
|------|------|------|
| **Node.js** 16+ | 执行部署脚本 | 必填 |
| **Git** | 读取分支、仓库信息 | 必填；Windows 若 IDE 内找不到 git，可设置环境变量 `PACVUE_GIT_PATH` |
| **GitHub CLI (`gh`)** | 发版 dispatch、状态轮询、取消 run | 推荐；执行 `gh auth login` 完成认证 |
| **GitHub Token** | 无 `gh` 时的发版 dispatch | 设置环境变量 `GITHUB_TOKEN` 或 `GH_TOKEN`（需 `repo` + `workflow` 权限）；轮询/取消仍依赖 `gh` |

### 项目

- 在 IDE 中打开 **Pacvue Commerce 仓库根目录**（含 `.github/workflows`）
- 部署脚本使用插件内置 `scripts/deploy-to-test.js`；若同机存在 `pacvue-commerce-deploy-extension/scripts/deploy-to-test.js` 也会自动识别

> **Windows 提示**：若终端能用 git/gh 但插件报错，多为 GUI 进程 PATH 与 shell 不一致。插件会尝试常见安装路径；仍失败时可设置 `PACVUE_GIT_PATH`、`PACVUE_GH_PATH` 指向官方可执行文件。

### 自动 Issue

触发失败或 Actions run 失败（非 `cancelled`）时，插件会默认在 `lizhenqiang-pacvue/pacvue-commerce-deploy-idea-plugin` 创建 `[auto-triage]` Issue。Issue 内容包含 branch、workflow、inputs、Run URL、失败 job 摘要、错误输出、项目 `.github` 配置快照，以及结构化诊断字段。

诊断字段包括：

| 字段 | 说明 |
|------|------|
| `failureCategory` | 失败分类，如 `script_parse_failed`、`workflow_not_found`、`invalid_project_config`、`github_cli_unavailable`、`github_auth_failed`、`dispatch_failed`、`workflow_failed` |
| `triageRoute` | 建议分流方向，如 `plugin_code`、`project_config`、`github_cli`、`github_auth`、`workflow_runtime` |
| `recommendedAction` | 给人工或自动化流程的下一步建议 |
| `labels` | 插件会在 Issue 创建成功后尝试追加 `auto-triage`、`deploy-failure` 和具体分类标签；标签不存在时不会影响 Issue 创建 |

为避免同类失败反复刷屏，插件会根据失败分类、workflow、branch、`ProjectName` 和归一化后的错误摘要生成 `dedupeFingerprint`。创建新 Issue 前会先搜索 open Issue；如果命中相同指纹，则不再新建 Issue，而是在已有 Issue 下追加一次失败 occurrence comment。

### 自动分流

仓库内置 `.github/workflows/deploy-issue-auto-triage.yml`，当 `[auto-triage]` Issue 创建、编辑或重新打开时自动运行。流程会解析 Issue 中的结构化 Payload，并按 `triageRoute` 分流：

| `triageRoute` | 自动动作 |
|------|------|
| `plugin_code` | 添加 `triage/plugin-code`、`needs-plugin-fix` 标签，并发出 `repository_dispatch` 事件 `pacvue-deploy-plugin-fix-request`，供后续源码修复 Agent 接入 |
| `project_config` | 添加 `triage/project-config`、`notify/project-owner` 标签，并在配置 SMTP secrets 后发送邮件通知 |
| `workflow_runtime` | 添加 `triage/workflow-runtime`、`notify/project-owner` 标签，并在配置 SMTP secrets 后发送邮件通知 |
| `github_auth` / `github_cli` / `deploy_input` | 添加 `needs-user-action` 标签并评论修复建议 |
| 其他 | 添加 `needs-investigation` 标签并评论人工排查建议 |

邮件通知使用以下 GitHub Secrets（未配置时会跳过发送邮件，但分流标签和评论仍会执行）：

| Secret | 说明 |
|------|------|
| `PACVUE_DEPLOY_TRIAGE_SMTP_HOST` | SMTP 服务地址 |
| `PACVUE_DEPLOY_TRIAGE_SMTP_PORT` | SMTP 端口，默认 `587` |
| `PACVUE_DEPLOY_TRIAGE_SMTP_USERNAME` | SMTP 用户名，可选 |
| `PACVUE_DEPLOY_TRIAGE_SMTP_PASSWORD` | SMTP 密码，可选 |
| `PACVUE_DEPLOY_TRIAGE_EMAIL_FROM` | 发件人 |
| `PACVUE_DEPLOY_TRIAGE_EMAIL_TO` | 收件人 |

可选环境变量：

| 变量 | 说明 |
|------|------|
| `PACVUE_DEPLOY_ISSUE_REPO` | 覆盖 Issue 目标仓库，格式 `owner/repo` |
| `PACVUE_DEPLOY_CREATE_ISSUE=false` | 临时关闭失败自动创建 Issue |

## 使用步骤

1. 用 IDE 打开 Pacvue Commerce 仓库根目录
2. 右侧工具栏 → **Pacvue Deploy**
3. 选择 **Target branch**、**Workflow**，填写动态参数
4. 如果当前项目有历史发版记录，可在 **Recent Deploys** 中点击 **Reuse** 一键复用；切换分支 / workflow 时也会自动回填匹配的历史参数
5. 点击 **Refresh** 重新加载分支与 workflow 列表（可选）
6. 点击 **Run**，插件会先校验输入，再在确认弹窗核对参数后点击 **Confirm Deploy**；在输出区查看状态与 Run URL
7. run 创建后可继续调整分支 / 配置并再次点击 **Run** 发起新发版；每条 Recent Deploys 会独立展示自己的 run 状态
8. 拿到 Run URL 后可在对应历史记录中点击 **Open Run** 打开 GitHub Actions 页面
9. 运行中的历史记录可点击 **Cancel** 取消对应 run
10. 需要时点击历史记录旁的 **Clear**，只删除当前项目中的对应记录

## 安装插件

从 [GitHub Releases](https://github.com/lizhenqiang-pacvue/pacvue-commerce-deploy-idea-plugin/releases) 下载 `pacvue-commerce-deploy-idea-plugin-*.zip`，或本地构建后安装：

```text
Settings / Preferences → Plugins → ⚙ → Install Plugin from Disk...
```

选择 zip 文件，重启 IDE。

当前最新版本：[v0.1.2](https://github.com/lizhenqiang-pacvue/pacvue-commerce-deploy-idea-plugin/releases/tag/v0.1.2)

## 更新说明

### v0.1.2

- Recent Deploys 按 Commerce 项目隔离缓存，支持展开 / 收起、最多 5 条滚动展示、逐条 **Reuse** / **Open Run** / **Cancel** / **Clear**
- 每条历史记录独立展示 GitHub Actions run 状态，运行中记录显示 **Cancel** 并隐藏 **Clear**
- Run 在 dispatch 完成后恢复可用，支持同一面板连续发起多次发版并分别跟踪历史状态
- 发版前增加确认弹窗与 required / choice / boolean 输入校验
- 发版成功时弹出 IDE 通知；触发失败或 run 失败时自动在 IDEA 插件仓库创建 `[auto-triage]` Issue

## 打包命令

需本机已安装 **Gradle**。项目通过 Gradle **Java Toolchain** 固定使用 **JDK 21** 编译（插件字节码目标仍为 Java 17）；若本机未安装 JDK 21，Gradle 会自动下载。

> **注意**：请勿用 JDK 25 作为 Gradle 运行 JDK，当前 Kotlin 1.9.25 与之不兼容，会导致 `Internal compiler error`。`build.gradle.kts` 中的 toolchain 配置已规避此问题。

```bash
# 打包插件（产物在 build/distributions/）
gradle buildPlugin

# 本地调试运行 IDE（沙箱环境）
gradle runIde

# 运行测试
gradle test
node --test src/test/js/deploy-to-test.test.js
```

若 toolchain 未生效、仍报 Kotlin 编译错误，可手动指定 JDK 21：

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
gradle --stop && gradle buildPlugin
```

打包成功后安装包路径：

```text
build/distributions/pacvue-commerce-deploy-idea-plugin-0.1.2.zip
```

发布到 GitHub Releases 示例：

```bash
gh release create v0.1.2 build/distributions/pacvue-commerce-deploy-idea-plugin-0.1.2.zip \
  --title "v0.1.2" --notes "Release notes..."
```

## 命令行验证（可选）

不打开插件面板，也可在项目根目录直接验证脚本：

```bash
node src/main/resources/scripts/deploy-to-test.js --list-workflows-json

node src/main/resources/scripts/deploy-to-test.js \
  --branch "test/sprint/q2-bus-3" \
  --workflow ".github/workflows/commerce-newui-test.yml" \
  --input ProjectName=commerce-newui-html-dev \
  --input environment=us-test \
  --input buildcmd=buildcommerce \
  --skip-last-run-inputs \
  --dry-run
```

## 常见问题

### 提示 `git is required`

确认 Git 已安装且 IDE 能访问；Windows 可设置 `PACVUE_GIT_PATH` 为 `git.exe` 完整路径。

### workflow 列表为空

确认 `.github/workflows` 下有带 `workflow_dispatch` 的 yml，且名称匹配「测试环境发版」（或通过 workflow 下拉手动选择）。

### 发版失败 / `gh` 相关错误

```bash
gh auth status
gh auth login
```

或配置 `GITHUB_TOKEN` / `GH_TOKEN` 后重试发版（需组织 SSO 授权时，在 GitHub Token 设置页对 Pacvue 组织 **Authorize SSO**）。

### Run 已触发但轮询失败

状态轮询与取消依赖官方 `gh`，请确保已安装并完成 `gh auth login`。

### `gradle buildPlugin` 报 Kotlin Internal compiler error

多为 Gradle 使用了 **JDK 25**（如新版 IntelliJ 自带 JBR）。请确认 `build.gradle.kts` 含 Java 21 toolchain，或按上文手动设置 `JAVA_HOME` 为 JDK 21 后执行 `gradle --stop && gradle buildPlugin`。

## 开发说明

- 源码：`src/main/kotlin`（UI 与进程调用）、`src/main/resources`（plugin.xml、图标、脚本）
- 图标源文件 `icons/faviconpacvue.ico` 仅用于本地生成 SVG，**不会**打入插件包
- 发布到 GitHub 时只需提交源码与配置，勿提交 `build/`、`.gradle/`、`.intellijPlatform/` 及 `*.zip`（见 `.gitignore`）
- Release 安装包请通过 GitHub Releases 分发，不要提交到仓库
