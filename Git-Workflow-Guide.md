# Git 工作台使用与协作规范指南

本指南旨在帮助团队成员快速掌握 Git 工作台（IDE 集成 Git 工具）的使用，规范代码提交与协作流程，确保项目代码的安全与一致性。

---

## 1. Git 核心概念回顾

在操作之前，理解 Git 的三个工作区域至关重要：

1.  **工作区 (Working Directory)**：你在 IDE 中直接修改的本地文件。
2.  **暂存区 (Staging Area / Index)**：准备提交的文件清单。
3.  **本地仓库 (Local Repository)**：已提交到本地的版本记录。
4.  **远程仓库 (Remote Repository)**：托管在云端（如 GitHub/GitLab/Gitee）的代码库。

---

## 2. IDEA Git 工作台基础操作

### 2.1 提交代码 (Commit)
- **快捷键**：`Ctrl + K` (Windows) / `Cmd + K` (macOS)
- **步骤**：
    1.  在左侧 **Commit** 面板勾选要提交的文件。
    2.  填写 **Commit Message**（提交信息）。
    3.  点击 **Commit**（仅提交到本地）或 **Commit and Push**（提交并推送到远程）。

### 2.2 拉取代码 (Pull)
- **快捷键**：`Ctrl + T` (Windows) / `Cmd + T` (macOS)
- **作用**：获取远程仓库的最新修改并合并到本地。
- **注意**：在开始工作前，养成先 **Pull** 的习惯，减少冲突概率。

### 2.3 推送代码 (Push)
- **快捷键**：`Ctrl + Shift + K` (Windows) / `Cmd + Shift + K` (macOS)
- **作用**：将本地仓库的提交同步到远程服务器。

### 2.4 分支管理 (Branches)
- **操作位置**：IDE 右下角的状态栏或 Git 面板。
- **新建分支**：`New Branch` -> 命名规范 `feature/功能名` 或 `fix/漏洞名`。
- **切换分支**：点击分支名选择 `Checkout`。

---

## 3. 规范化提交信息 (Commit Message)

良好的提交信息是项目历史的可读性保证。推荐采用以下格式：

`<type>: <description>`

- **feat**: 新功能 (feature)
- **fix**: 修补 bug
- **docs**: 文档修改 (documentation)
- **style**: 格式变动（不影响代码运行）
- **refactor**: 重构（即不是新增功能，也不是修改 bug 的代码变动）
- **test**: 增加测试
- **chore**: 构建过程或辅助工具的变动

**示例**：`feat: 增加荣誉树留言删除功能`

---

## 4. 冲突解决 (Conflict Resolution)

当多人修改了同一个文件的同一行代码时，Git 无法自动合并，会产生冲突。

1.  **触发**：通常在 `Pull` 或 `Merge` 时发生。
2.  **操作**：
    - IDE 会弹出 **Conflicts** 对话框。
    - 点击 **Merge** 进入对比界面。
    - **左侧**为本地代码，**右侧**为远程代码，**中间**为合并结果。
    - 点击箭头（`<<` 或 `>>`）选择保留的代码。
    - 解决完所有冲突后，点击 **Apply**。

---

## 5. 常用 Git 指令备查 (Terminal)

虽然 IDE 提供了图形界面，但在某些情况下命令行更高效：

```bash
# 查看状态
git status

# 暂存所有修改
git add .

# 提交并说明
git commit -m "feat: 关键功能描述"

# 推送到远程分支
git push origin master

# 查看提交历史
git log --oneline --graph
```

---

## 6. 避坑指南与最佳实践

1.  **不要直接在 Master 分支开发**：使用 `feature` 分支开发，完成后再发起 `Merge Request`。
2.  **小步提交**：一个 Commit 只做一件事，不要积压了大量修改后一次性提交。
3.  **忽略不必要的文件**：确保 `.gitignore` 文件配置正确，避免提交 `target/`、`.idea/`、`node_modules/` 等文件。
4.  **提交前自测**：确保本地通过 `./mvnw clean test` 后再推送。

---

*本指南适用于 CDAME 项目团队。如有疑问，请咨询项目负责人。*
