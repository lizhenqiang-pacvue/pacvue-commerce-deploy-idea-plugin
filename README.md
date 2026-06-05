# Pacvue Commerce Deploy IDEA Plugin

在 JetBrains IDE（IntelliJ IDEA、WebStorm 等）右侧提供 **Pacvue Deploy** 工具窗口，用于选择测试分支、workflow 与输入参数，触发 Pacvue Commerce 测试环境 GitHub Actions 发版，并轮询运行状态。

与 [pacvue-commerce-deploy-extension](https://github.com/pacvue/pacvue-commerce-deploy-extension)（Cursor / VS Code 版）共用同一套 `deploy-to-test.js` 能力，入口独立。

## 功能

- 读取当前项目 Git 分支，下拉选择目标测试分支
- 扫描 `.github/workflows` 下支持 `workflow_dispatch` 的 workflow（默认匹配名称含「测试环境发版」）
- 根据 workflow inputs 动态生成表单
- 点击 **Run** 触发发版，在输出区展示命令预览与 Run URL
- 约每 5 秒轮询 GitHub Actions 状态，支持 **Cancel** 取消进行中的 run

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

## 使用步骤

1. 用 IDE 打开 Pacvue Commerce 仓库根目录
2. 右侧工具栏 → **Pacvue Deploy**
3. 选择 **Target branch**、**Workflow**，填写动态参数
4. 点击 **Refresh** 重新加载分支与 workflow 列表（可选）
5. 点击 **Run** 触发发版；在输出区查看状态与 Run URL
6. 运行中可点击 **Cancel** 取消

## 安装插件

从 Release 或本地构建产物安装：

```text
Settings / Preferences → Plugins → ⚙ → Install Plugin from Disk...
```

选择 `build/distributions/pacvue-commerce-deploy-idea-plugin-*.zip`，重启 IDE。

## 打包命令

需本机已安装 **JDK 17+** 与 **Gradle**（或使用 Gradle Wrapper）。

```bash
# 打包插件（产物在 build/distributions/）
gradle buildPlugin

# 本地调试运行 IDE（沙箱环境）
gradle runIde

# 运行测试
gradle test
node --test src/test/js/deploy-to-test.test.js
```

打包成功后安装包路径：

```text
build/distributions/pacvue-commerce-deploy-idea-plugin-0.1.0.zip
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

## 开发说明

- 源码：`src/main/kotlin`（UI 与进程调用）、`src/main/resources`（plugin.xml、图标、脚本）
- 图标源文件 `icons/faviconpacvue.ico` 仅用于本地生成 SVG，**不会**打入插件包
- 发布到 GitHub 时只需提交源码与配置，勿提交 `build/`、`.gradle/`、`.intellijPlatform/` 及 `*.zip`（见 `.gitignore`）
- Release 安装包请通过 GitHub Releases 分发，不要提交到仓库
