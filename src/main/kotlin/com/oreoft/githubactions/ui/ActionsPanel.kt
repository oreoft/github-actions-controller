package com.oreoft.githubactions.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.oreoft.githubactions.GitHubActionsBundle.message
import com.oreoft.githubactions.api.GitHubApiClient
import com.oreoft.githubactions.api.GitHubWorkflow
import com.oreoft.githubactions.api.GitHubWorkflowRun
import com.oreoft.githubactions.api.OwnerRepo
import com.oreoft.githubactions.auth.GitHubAuthService
import com.oreoft.githubactions.git.GitRepoDetector
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * GitHub Actions 主面板。
 * 所有 UI 文本通过 [com.oreoft.githubactions.GitHubActionsBundle] 获取，支持多语言。
 */
class ActionsPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ─── Models ────────────────────────────────────────────────────────────────
    private val workflowModel = DefaultListModel<GitHubWorkflow>()
    private val runModel = DefaultListModel<GitHubWorkflowRun>()

    // ─── Views ─────────────────────────────────────────────────────────────────
    private val workflowList = JBList(workflowModel).apply {
        cellRenderer = WorkflowCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = message("list.workflows.empty")
    }
    private val runList = JBList(runModel).apply {
        cellRenderer = RunCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = message("list.runs.empty")
    }
    private val statusLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    // ─── State ─────────────────────────────────────────────────────────────────
    private var ownerRepo: OwnerRepo? = null
    private var selectedWorkflow: GitHubWorkflow? = null
    private var currentAccount: GithubAccount? = null

    init {
        setupUI()
        refresh()
    }

    // ─── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        border = JBUI.Borders.empty()

        val toolbar = createToolbar()
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        workflowList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                selectedWorkflow = workflowList.selectedValue ?: return@addListSelectionListener
                selectedWorkflow?.let { loadRuns(it) }
            }
        }

        runList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    runList.selectedValue?.let { BrowserUtil.browse(it.htmlUrl) }
                }
            }
        })

        val splitter = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = buildTitledPane(message("panel.workflows.title"), workflowList)
            secondComponent = buildTitledPane(message("panel.runs.title"), runList)
        }
        add(splitter, BorderLayout.CENTER)

        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }, BorderLayout.SOUTH)
    }

    private fun buildTitledPane(title: String, list: JBList<*>): JPanel =
        JPanel(BorderLayout()).apply {
            add(JBLabel("  $title").apply {
                border = JBUI.Borders.empty(5, 6)
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {

            add(object : AnAction(
                message("action.refresh.text"),
                message("action.refresh.description"),
                AllIcons.Actions.Refresh
            ) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })

            addSeparator()

            add(object : AnAction(
                message("action.trigger.text"),
                message("action.trigger.description"),
                AllIcons.Actions.Execute
            ) {
                override fun actionPerformed(e: AnActionEvent) = triggerWorkflow()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedWorkflow != null && currentAccount != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

            addSeparator()

            add(AccountSwitcherAction())
        }

        return ActionManager.getInstance()
            .createActionToolbar("GitHubActionsToolbar", group, true)
    }

    /**
     * 工具栏账号切换下拉框。
     * 列出所有 IDEA GitHub 账号，当前账号勾选，点击切换并自动刷新。
     */
    private inner class AccountSwitcherAction : ComboBoxAction() {

        override fun update(e: AnActionEvent) {
            val acc = currentAccount
            e.presentation.text = acc?.name ?: message("action.account.no.login")
            e.presentation.icon = AllIcons.General.User
            e.presentation.description = message(
                "action.account.description",
                acc?.name ?: message("action.account.no.login")
            )
        }

        override fun createPopupActionGroup(
            button: javax.swing.JComponent,
            dataContext: DataContext
        ): DefaultActionGroup {
            val popupGroup = DefaultActionGroup()
            val accounts = GitHubAuthService.getAccounts()

            if (accounts.isEmpty()) {
                popupGroup.add(object : AnAction(message("action.account.no.accounts")) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub")
                    }
                })
            } else {
                for (account in accounts) {
                    val isCurrent = account == currentAccount
                    popupGroup.add(object : AnAction(
                        message("action.account.item.label", account.name, account.server.host),
                        message("action.account.item.description"),
                        if (isCurrent) AllIcons.Actions.Checked else AllIcons.General.User
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (!isCurrent) {
                                currentAccount = account
                                refresh()
                            }
                        }
                    })
                }
                popupGroup.addSeparator()
                popupGroup.add(object : AnAction(
                    message("action.account.manage"),
                    message("action.account.manage.description"),
                    AllIcons.General.Settings
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "GitHub")
                    }
                })
            }
            return popupGroup
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    // ─── Data Loading ──────────────────────────────────────────────────────────

    fun refresh() {
        ownerRepo = GitRepoDetector.detect(project)
        val ownerRepoLocal = ownerRepo
        if (ownerRepoLocal == null) {
            setStatus(message("status.no.remote"))
            return
        }

        val accounts = GitHubAuthService.getAccounts()
        if (accounts.isEmpty()) {
            setStatus(message("status.no.account"))
            workflowModel.clear()
            runModel.clear()
            return
        }

        // 优先匹配 remote host 的账号；若 currentAccount 已设置且在列表中则保持不变
        val host = ownerRepoLocal.host
        if (currentAccount == null || currentAccount !in accounts) {
            currentAccount = GitHubAuthService.findAccountFor(host) ?: accounts.first()
        }
        val account = currentAccount!!

        setStatus(message("status.loading", account.name, ownerRepoLocal))

        runInBackground {
            val token = GitHubAuthService.getToken(account)
            if (token == null) {
                onEdt { setStatus(message("status.token.missing")) }
                return@runInBackground
            }
            try {
                val workflows = GitHubApiClient(token).listWorkflows(ownerRepoLocal.owner, ownerRepoLocal.repo)
                onEdt {
                    workflowModel.clear()
                    workflows.forEach { workflowModel.addElement(it) }
                    setStatus(message("status.workflows.loaded", workflows.size, account.name, ownerRepoLocal))
                    if (workflows.isNotEmpty() && workflowList.selectedIndex < 0) {
                        workflowList.selectedIndex = 0
                    }
                }
            } catch (ex: Exception) {
                onEdt { setStatus(message("status.error", ex.message ?: "")) }
            }
        }
    }

    private fun loadRuns(workflow: GitHubWorkflow) {
        val (owner, repo) = ownerRepo ?: return
        val account = currentAccount ?: return

        runModel.clear()
        setStatus(message("status.runs.loading", workflow.name))

        runInBackground {
            val token = GitHubAuthService.getToken(account) ?: run {
                onEdt { setStatus(message("status.token.expired")) }
                return@runInBackground
            }
            try {
                val runs = GitHubApiClient(token).listWorkflowRuns(owner, repo, workflow.id)
                onEdt {
                    runModel.clear()
                    runs.forEach { runModel.addElement(it) }
                    setStatus(
                        if (runs.isEmpty()) message("status.runs.empty", workflow.name)
                        else message("status.runs.loaded", runs.size, workflow.name)
                    )
                }
            } catch (ex: Exception) {
                onEdt { setStatus(message("status.error", ex.message ?: "")) }
            }
        }
    }

    // ─── Actions ───────────────────────────────────────────────────────────────

    private fun triggerWorkflow() {
        val wf = selectedWorkflow ?: return
        val (owner, repo) = ownerRepo ?: return
        val account = currentAccount ?: return

        val branch = Messages.showInputDialog(
            project,
            message("dialog.trigger.message"),
            message("dialog.trigger.title", wf.name),
            AllIcons.Actions.Execute,
            message("dialog.trigger.default.branch"),
            null
        ) ?: return
        if (branch.isBlank()) return

        setStatus(message("status.trigger.sending", wf.name, branch))

        runInBackground {
            val token = GitHubAuthService.getToken(account) ?: run {
                onEdt { setStatus(message("status.token.expired")) }
                return@runInBackground
            }
            try {
                GitHubApiClient(token).triggerWorkflow(owner, repo, wf.id, branch)
                onEdt {
                    setStatus(message("status.trigger.success", wf.name, branch))
                    Timer(2000) { loadRuns(wf) }.apply { isRepeats = false; start() }
                }
            } catch (ex: Exception) {
                onEdt {
                    setStatus(message("status.trigger.failed"))
                    Messages.showErrorDialog(
                        project,
                        ex.message ?: "",
                        message("dialog.trigger.error.title")
                    )
                }
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun setStatus(msg: String) { statusLabel.text = msg }
    private fun runInBackground(block: () -> Unit) { AppExecutorUtil.getAppExecutorService().submit(block) }
    private fun onEdt(block: () -> Unit) { SwingUtilities.invokeLater(block) }
}

// ─── Cell Renderers ───────────────────────────────────────────────────────────

class WorkflowCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val wf = value as? GitHubWorkflow
        val label = super.getListCellRendererComponent(
            list, wf?.name ?: value, index, isSelected, cellHasFocus
        ) as JLabel
        label.border = JBUI.Borders.empty(5, 8)
        label.icon = if (wf?.state == "active") AllIcons.RunConfigurations.TestPassed
                     else AllIcons.RunConfigurations.TestIgnored
        return label
    }
}

class RunCellRenderer : DefaultListCellRenderer() {
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val run = value as? GitHubWorkflowRun
        val text = run?.let {
            val t = runCatching { fmt.format(Instant.parse(it.createdAt)) }.getOrDefault("")
            val msg = it.headCommit?.message?.lines()?.firstOrNull()?.take(60) ?: ""
            "#${it.runNumber}  [${it.headBranch}]  $msg  · $t"
        } ?: value

        val label = super.getListCellRendererComponent(
            list, text, index, isSelected, cellHasFocus
        ) as JLabel
        label.border = JBUI.Borders.empty(5, 8)
        label.icon = runStatusIcon(run)
        label.toolTipText = run?.let {
            "status: ${it.status}  conclusion: ${it.conclusion ?: "—"}  double-click to open"
        }
        return label
    }

    private fun runStatusIcon(run: GitHubWorkflowRun?): Icon = when {
        run == null -> AllIcons.RunConfigurations.TestIgnored
        run.status == "in_progress" || run.status == "waiting" -> AllIcons.RunConfigurations.TestPaused
        run.status == "queued" -> AllIcons.RunConfigurations.TestNotRan
        run.conclusion == "success" -> AllIcons.RunConfigurations.TestPassed
        run.conclusion == "failure" -> AllIcons.RunConfigurations.TestFailed
        run.conclusion == "cancelled" -> AllIcons.Actions.Cancel
        run.conclusion == "skipped" -> AllIcons.RunConfigurations.TestSkipped
        run.conclusion == "action_required" -> AllIcons.General.BalloonWarning
        else -> AllIcons.RunConfigurations.TestIgnored
    }
}
